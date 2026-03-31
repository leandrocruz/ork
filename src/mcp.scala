package ork.mcp

import zio.*
import zio.json.*
import zio.json.ast.Json
import better.files.*
import better.files.File.*
import ork.domain.*
import ork.task.TaskPaths
import ork.worktree.Worktrees

// JSON-RPC 2.0 types

case class JsonRpcRequest(
  jsonrpc : String,
  id      : Option[Json],
  method  : String,
  params  : Option[Json]
) derives JsonCodec

case class JsonRpcResponse(
  jsonrpc : String,
  id      : Option[Json],
  result  : Option[Json]  = None,
  error   : Option[Json]  = None
) derives JsonCodec

case class TaskRef(flowType: FlowType, name: String)

object TaskRef:
  def parse(taskId: String): Either[String, TaskRef] =
    taskId.split("/", 2) match
      case Array(ft, name) =>
        FlowType.fromString(ft) match
          case Some(flowType) => Right(TaskRef(flowType, name))
          case None           => Left(s"Unknown flow type: '$ft'. Expected: feature, hotfix, release")
      case _ => Left(s"Invalid task identifier: '$taskId'. Expected format: feature/<name>")

object McpServer:

  def run(taskId: String): Task[Unit] =
    for
      ref    <- ZIO.fromEither(TaskRef.parse(taskId))
                  .mapError(msg => new Exception(msg))
      config <- TaskPaths.loadConfig
      sid    <- Ref.make("")
      _      <- loop(config, ref, sid)
    yield ()

  private def loop(config: OrkConfig, ref: TaskRef, sid: Ref[String]): Task[Unit] =
    val readLine = ZIO.attemptBlocking(Option(scala.io.StdIn.readLine()))

    readLine.flatMap {
      case None       => ZIO.unit
      case Some(line) =>
        for
          request  <- ZIO.fromEither(line.fromJson[JsonRpcRequest])
                        .mapError(msg => new Exception(s"Invalid JSON-RPC: $msg"))
          response <- handle(config, ref, sid, request)
          _        <- response match
                        case Some(r) => ZIO.attempt(println(r.toJson))
                        case None    => ZIO.unit
          _        <- loop(config, ref, sid)
        yield ()
    }

  private def handle(config: OrkConfig, ref: TaskRef, sid: Ref[String], req: JsonRpcRequest): Task[Option[JsonRpcResponse]] =
    req.method match
      case "initialize"                => handleInitialize(ref, sid, req)
      case "notifications/initialized" => ZIO.none
      case "tools/list"                => handleToolsList(req)
      case "tools/call"                => handleToolsCall(config, ref, sid, req)
      case _                           => ZIO.some(errorResponse(req.id, -32601, s"Unknown method: ${req.method}"))

  private def handleInitialize(ref: TaskRef, sid: Ref[String], req: JsonRpcRequest): Task[Option[JsonRpcResponse]] =
    val sessionId = java.util.UUID.randomUUID().toString.take(8)
    val now       = java.time.Instant.now().toString
    val entry     = SessionEntry(id = sessionId, date = now)

    val result = Json.Obj(
      "protocolVersion" -> Json.Str("2024-11-05"),
      "capabilities"    -> Json.Obj(
        "tools" -> Json.Obj()
      ),
      "serverInfo" -> Json.Obj(
        "name"    -> Json.Str("ork"),
        "version" -> Json.Str("0.1.0")
      )
    )

    for
      _   <- sid.set(sessionId)
      _   <- registerSession(ref, entry)
      resp = JsonRpcResponse(jsonrpc = "2.0", id = req.id, result = Some(result))
    yield Some(resp)

  private def registerSession(ref: TaskRef, entry: SessionEntry): Task[Unit] =
    val file = TaskPaths.manifest(ref.flowType, ref.name)
    for
      content  <- ZIO.attempt(file.contentAsString)
      manifest <- ZIO.fromEither(content.fromJson[TaskManifest])
                    .mapError(msg => new Exception(s"Invalid manifest: $msg"))
      updated   = manifest.copy(sessions = manifest.sessions :+ entry)
      _        <- ZIO.attempt(file.overwrite(updated.toJsonPretty))
    yield ()

  private def handleToolsList(req: JsonRpcRequest): Task[Option[JsonRpcResponse]] =
    val tools = Json.Obj(
      "tools" -> Json.Arr(
        Json.Obj(
          "name"        -> Json.Str("task_add_repo"),
          "description" -> Json.Str("Add a git repository to the current task. Creates a git worktree with the appropriate branch under the task directory. The repo must exist under one of the configured project roots."),
          "inputSchema" -> Json.Obj(
            "type"       -> Json.Str("object"),
            "properties" -> Json.Obj(
              "repo" -> Json.Obj(
                "type"        -> Json.Str("string"),
                "description" -> Json.Str("Name of the git repository to add (e.g. 'my-service')")
              )
            ),
            "required" -> Json.Arr(Json.Str("repo"))
          )
        ),
        Json.Obj(
          "name"        -> Json.Str("task_update_changelog"),
          "description" -> Json.Str("Update the changelog for a repository in the current task. Call this after making changes to a repo to document what was done. The content should be a concise summary in markdown format using bullet points."),
          "inputSchema" -> Json.Obj(
            "type"       -> Json.Str("object"),
            "properties" -> Json.Obj(
              "repo" -> Json.Obj(
                "type"        -> Json.Str("string"),
                "description" -> Json.Str("Name of the repository (e.g. 'my-service')")
              ),
              "summary" -> Json.Obj(
                "type"        -> Json.Str("string"),
                "description" -> Json.Str("Markdown summary of changes made to this repo (use bullet points)")
              )
            ),
            "required" -> Json.Arr(Json.Str("repo"), Json.Str("summary"))
          )
        ),
        Json.Obj(
          "name"        -> Json.Str("task_save_session"),
          "description" -> Json.Str("Save a summary of the current session. Call this before ending the session. Include what was accomplished, decisions made, and any open items for the next session."),
          "inputSchema" -> Json.Obj(
            "type"       -> Json.Str("object"),
            "properties" -> Json.Obj(
              "summary" -> Json.Obj(
                "type"        -> Json.Str("string"),
                "description" -> Json.Str("Markdown summary of the session: what was done, decisions made, and open items for next session")
              )
            ),
            "required" -> Json.Arr(Json.Str("summary"))
          )
        ),
        Json.Obj(
          "name"        -> Json.Str("task_status"),
          "description" -> Json.Str("Get the current task status including all repos, branches, and worktree paths."),
          "inputSchema" -> Json.Obj(
            "type"       -> Json.Str("object"),
            "properties" -> Json.Obj()
          )
        )
      )
    )
    ZIO.some(JsonRpcResponse(jsonrpc = "2.0", id = req.id, result = Some(tools)))

  private def handleToolsCall(config: OrkConfig, ref: TaskRef, sid: Ref[String], req: JsonRpcRequest): Task[Option[JsonRpcResponse]] =

    def extractParam(params: Json, key: String): Task[String] =
      ZIO.fromOption(
        params.asObject
          .flatMap(_.get("arguments"))
          .flatMap(_.asObject)
          .flatMap(_.get(key))
          .flatMap(_.asString)
      ).orElseFail(new Exception(s"Missing parameter: $key"))

    def toolName(params: Json): Task[String] =
      ZIO.fromOption(
        params.asObject.flatMap(_.get("name")).flatMap(_.asString)
      ).orElseFail(new Exception("Missing tool name"))

    def textResult(text: String): Json =
      Json.Obj(
        "content" -> Json.Arr(
          Json.Obj(
            "type" -> Json.Str("text"),
            "text" -> Json.Str(text)
          )
        )
      )

    def errorResult(text: String): Json =
      Json.Obj(
        "content" -> Json.Arr(
          Json.Obj(
            "type" -> Json.Str("text"),
            "text" -> Json.Str(text)
          )
        ),
        "isError" -> Json.Bool(true)
      )

    val params = req.params.getOrElse(Json.Obj())

    val effect = for
      name   <- toolName(params)
      result <- name match
                  case "task_add_repo" =>
                    for
                      repo  <- extractParam(params, "repo")
                      entry <- Worktrees.add(config, ref.flowType, ref.name, repo)
                    yield textResult(s"Added repo '${entry.name}' to task '${ref.name}'.\nWorktree: ${entry.worktree}\nBranch: ${entry.branch}\nBase: ${entry.baseBranch}")
                  case "task_update_changelog" =>
                    for
                      repo      <- extractParam(params, "repo")
                      summary   <- extractParam(params, "summary")
                      sessionId <- sid.get
                      _         <- writeChangelog(ref, sessionId, repo, summary)
                    yield textResult(s"Changelog updated for '$repo'.")
                  case "task_save_session" =>
                    for
                      summary   <- extractParam(params, "summary")
                      sessionId <- sid.get
                      _         <- saveSession(ref, sessionId, summary)
                    yield textResult(s"Session summary saved.")
                  case "task_status" =>
                    for
                      content  <- ZIO.attempt(TaskPaths.manifest(ref.flowType, ref.name).contentAsString)
                      manifest <- ZIO.fromEither(content.fromJson[TaskManifest])
                                    .mapError(msg => new Exception(s"Invalid manifest: $msg"))
                    yield textResult(manifest.toJsonPretty)
                  case other =>
                    ZIO.succeed(errorResult(s"Unknown tool: $other"))
    yield result

    effect
      .map(r => Some(JsonRpcResponse(jsonrpc = "2.0", id = req.id, result = Some(r))))
      .catchAll(err => ZIO.some(JsonRpcResponse(jsonrpc = "2.0", id = req.id, result = Some(errorResult(err.getMessage)))))

  private def writeChangelog(ref: TaskRef, sessionId: String, repoName: String, summary: String): Task[Unit] =
    val changelogDir  = TaskPaths.taskDir(ref.flowType, ref.name) / "changelog"
    val changelogFile = changelogDir / s"$repoName.md"
    val now           = java.time.Instant.now().toString.take(10)
    val entry         = s"\n## Session $sessionId — $now\n\n$summary\n"

    ZIO.attempt {
      changelogDir.createDirectories()
      if changelogFile.exists then changelogFile.appendLine(entry)
      else changelogFile.overwrite(s"# Changelog: $repoName\n$entry")
    }

  private def saveSession(ref: TaskRef, sessionId: String, summary: String): Task[Unit] =
    val sessionsDir = TaskPaths.taskDir(ref.flowType, ref.name) / "sessions"
    val sessionFile = sessionsDir / s"$sessionId.md"
    val now         = java.time.Instant.now().toString

    ZIO.attempt {
      sessionsDir.createDirectories()
      sessionFile.overwrite(s"# Session $sessionId — $now\n\n$summary\n")
    }

  private def errorResponse(id: Option[Json], code: Int, message: String): JsonRpcResponse =
    JsonRpcResponse(
      jsonrpc = "2.0",
      id      = id,
      error   = Some(Json.Obj(
        "code"    -> Json.Num(code),
        "message" -> Json.Str(message)
      ))
    )
