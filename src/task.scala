package ork.task

import zio.*
import zio.json.*
import better.files.*
import better.files.File.*
import ork.domain.*

object TaskPaths:
  val orkHome   : File = home / ".ork"
  val tasksDir  : File = orkHome / "tasks"
  val configFile: File = orkHome / "config.json"
  val promptTemplate: File              = orkHome / "prompt.md"

  def typeDir(flowType: FlowType): File  = tasksDir / flowType.branchPrefix
  def taskDir(flowType: FlowType, name: String): File = typeDir(flowType) / name
  def manifest(flowType: FlowType, name: String): File     = taskDir(flowType, name) / "ork.json"
  def log(flowType: FlowType, name: String): File          = taskDir(flowType, name) / "log.md"
  def mcpConfig(flowType: FlowType, name: String): File    = taskDir(flowType, name) / "mcp.json"
  def promptFile(flowType: FlowType, name: String): File   = taskDir(flowType, name) / "prompt.md"
  def worktrees(flowType: FlowType, name: String): File    = taskDir(flowType, name) / "worktrees"

  def findAllTasks: Seq[(FlowType, TaskManifest)] =
    FlowType.values.toSeq.flatMap { ft =>
      val dir = typeDir(ft)
      if !dir.exists then Seq.empty
      else
        dir.glob("**/ork.json").flatMap { f =>
          f.contentAsString.fromJson[TaskManifest].toOption.map(m => (ft, m))
        }.toSeq
    }

  def findTask(name: String): Seq[(FlowType, TaskManifest)] =
    FlowType.values.toSeq.flatMap { ft =>
      val m = manifest(ft, name)
      if !m.exists then None
      else m.contentAsString.fromJson[TaskManifest].toOption.map(man => (ft, man))
    }

  def loadConfig: zio.Task[OrkConfig] =
    if !configFile.exists then ZIO.succeed(OrkConfig.empty)
    else
      for
        content <- ZIO.attempt(configFile.contentAsString)
        config  <- ZIO.fromEither(content.fromJson[OrkConfig])
                     .mapError(msg => new Exception(s"Invalid config: $msg"))
      yield config

enum Ide:
  case IntelliJ, VsCode

case class OpenCommand(name: String, ide: Ide) extends Command:

  override def execute: Task[String] =
    for
      found   <- ZIO.attempt(TaskPaths.findTask(name))
      (ft, m) <- found match
                   case Seq()    => ZIO.fail(new Exception(s"Task '$name' not found"))
                   case Seq(one) => ZIO.succeed(one)
                   case multiple =>
                     val types = multiple.map(_._1.branchPrefix).mkString(", ")
                     ZIO.fail(new Exception(s"Task '$name' exists in multiple types: $types"))
      taskDir  = TaskPaths.taskDir(ft, name)
      _       <- ide match
                   case Ide.IntelliJ =>
                     ZIO.attempt {
                       import sys.process.*
                       Process(Seq("open", "-na", "IntelliJ IDEA", "--args", taskDir.canonicalPath)).!
                     }
                   case Ide.VsCode =>
                     val wsFile = taskDir / s"$name.code-workspace"
                     if !wsFile.exists then ZIO.fail(new Exception(s"No workspace file found. Add a repo first."))
                     else ZIO.attempt {
                       import sys.process.*
                       Process(Seq("code", wsFile.canonicalPath)).!
                     }
    yield s"Opening '$name' in ${ide.toString}"

case class ResumeCommand(name: String) extends Command:

  override def execute: Task[String] =
    for
      found <- ZIO.attempt(TaskPaths.findTask(name))
      (ft, m) <- found match
                   case Seq()        => ZIO.fail(new Exception(s"Task '$name' not found"))
                   case Seq(one)     => ZIO.succeed(one)
                   case multiple     =>
                     val types = multiple.map(_._1.branchPrefix).mkString(", ")
                     ZIO.fail(new Exception(s"Task '$name' exists in multiple types: $types"))
      sessionName = s"ork-${ft.branchPrefix}-$name"
      mcpFile     = TaskPaths.mcpConfig(ft, name).canonicalPath
      promptFile  = TaskPaths.promptFile(ft, name).canonicalPath
      wtDir       = TaskPaths.worktrees(ft, name).canonicalPath
      cmd         = s"""claude --resume $sessionName --mcp-config $mcpFile --append-system-prompt-file $promptFile --add-dir $wtDir --allowedTools "mcp__ork__task_add_repo,mcp__ork__task_update_changelog,mcp__ork__task_save_session,mcp__ork__task_status,Bash(ork task *)""""
      _          <- ZIO.attempt {
                      import sys.process.*
                      Process(Seq("sh", "-c", cmd)).!
                    }
    yield s"Session for '$name' ended."

case class InfoCommand(name: String) extends Command:

  private val RESET  = "\u001b[0m"
  private val BOLD   = "\u001b[1m"
  private val CYAN   = "\u001b[36m"
  private val GREEN  = "\u001b[32m"
  private val YELLOW = "\u001b[33m"
  private val RED    = "\u001b[31m"
  private val BLUE   = "\u001b[34m"
  private val DIM    = "\u001b[2m"

  private def colorForType(ft: FlowType): String = ft match
    case FlowType.Feature => GREEN
    case FlowType.Hotfix  => RED
    case FlowType.Release => YELLOW

  private def colorForStatus(s: TaskStatus): String = s match
    case TaskStatus.Active   => GREEN
    case TaskStatus.Paused   => YELLOW
    case TaskStatus.Finished => BLUE
    case TaskStatus.Aborted  => RED

  override def execute: Task[String] =
    for
      found <- ZIO.attempt(TaskPaths.findTask(name))
      result <- found match
                  case Seq()          => ZIO.fail(new Exception(s"Task '$name' not found"))
                  case Seq((ft, m))   => render(ft, m)
                  case multiple       =>
                    val types = multiple.map(_._1.branchPrefix).mkString(", ")
                    ZIO.fail(new Exception(s"Task '$name' exists in multiple types: $types. Use the full path."))
    yield result

  private def repoChanges(r: RepoEntry): Task[Option[(String, String)]] =
    import better.files.File
    val wtDir = File(r.worktree)
    if !wtDir.exists then ZIO.none
    else
      val git = ork.repo.Repos.runGit(wtDir, _)
      for
        log  <- git(s"git log --oneline ${r.baseCommit}..HEAD").map(_.trim).catchAll(_ => ZIO.succeed(""))
        stat <- git(s"git diff --stat ${r.baseCommit}..HEAD").map(_.trim).catchAll(_ => ZIO.succeed(""))
      yield
        if log.isEmpty && stat.isEmpty then None
        else Some((log, stat))

  private def readChangelog(ft: FlowType, repoName: String): Option[String] =
    val file = TaskPaths.taskDir(ft, name) / "changelog" / s"$repoName.md"
    if file.exists then Some(file.contentAsString) else None

  private def render(ft: FlowType, m: TaskManifest): Task[String] =
    val typeColor = colorForType(ft)
    val sb = new StringBuilder

    for
      config  <- TaskPaths.loadConfig
      changes <- ZIO.foreach(m.repos)(r => repoChanges(r).map(c => (r, c)))
    yield
      sb.append(s"${BOLD}${typeColor}${ft.branchPrefix}${RESET}${BOLD}/$name${RESET}\n")
      sb.append(s"\n")
      sb.append(s"  Status:   ${colorForStatus(m.status)}${m.status.toString.toLowerCase}${RESET}\n")
      sb.append(s"  Branch:   ${CYAN}${m.branch(config.developer)}${RESET}\n")
      sb.append(s"  Base:     ${m.flowType.baseBranch}\n")
      sb.append(s"  Created:  ${m.created}\n")

      if m.description.nonEmpty then
        sb.append(s"  Desc:     ${m.description}\n")

      if m.repos.nonEmpty then
        sb.append(s"\n  ${BOLD}Repositories${RESET}\n")
        changes.foreach { (r, c) =>
          sb.append(s"\n  ${BOLD}${r.name}${RESET}\n")
          sb.append(s"    Origin:      ${DIM}${r.origin}${RESET}\n")
          sb.append(s"    Worktree:    ${DIM}${r.worktree}${RESET}\n")
          sb.append(s"    Branch:      ${CYAN}${r.branch}${RESET}\n")
          sb.append(s"    Base:        ${r.baseBranch} ${DIM}(${r.baseCommit})${RESET}\n")
          c match
            case None =>
              sb.append(s"    Changes:     ${DIM}none${RESET}\n")
            case Some((log, stat)) =>
              sb.append(s"    ${BOLD}Commits${RESET}\n")
              log.linesIterator.foreach(l => sb.append(s"      ${YELLOW}${l}${RESET}\n"))
              sb.append(s"    ${BOLD}Stats${RESET}\n")
              stat.linesIterator.foreach(l => sb.append(s"      ${l}\n"))
          readChangelog(ft, r.name).foreach { cl =>
            sb.append(s"    ${BOLD}Changelog${RESET}\n")
            cl.linesIterator.foreach(l => sb.append(s"      ${l}\n"))
          }
        }
      else
        sb.append(s"\n  ${DIM}No repositories added yet.${RESET}\n")

      if m.sessions.nonEmpty then
        sb.append(s"\n  ${BOLD}Sessions${RESET}\n")
        m.sessions.foreach { s =>
          sb.append(s"    ${s.date} ${DIM}(${s.id})${RESET}\n")
        }

      sb.toString

case class ListCommand() extends Command:

  private val RESET  = "\u001b[0m"
  private val BOLD   = "\u001b[1m"
  private val CYAN   = "\u001b[36m"
  private val GREEN  = "\u001b[32m"
  private val YELLOW = "\u001b[33m"
  private val BLUE   = "\u001b[34m"
  private val RED    = "\u001b[31m"

  private def colorForType(ft: FlowType): String = ft match
    case FlowType.Feature => GREEN
    case FlowType.Hotfix  => RED
    case FlowType.Release => YELLOW

  private def colorForStatus(s: TaskStatus): String = s match
    case TaskStatus.Active   => GREEN
    case TaskStatus.Paused   => YELLOW
    case TaskStatus.Finished => BLUE
    case TaskStatus.Aborted  => RED

  override def execute: Task[String] =
    for
      config <- TaskPaths.loadConfig
      tasks  <- ZIO.attempt(TaskPaths.findAllTasks)
    yield
      if tasks.isEmpty then "No tasks found."
      else
        val header = (s"${BOLD}Type${RESET}", s"${BOLD}Name${RESET}", s"${BOLD}Status${RESET}", s"${BOLD}Branch${RESET}", s"${BOLD}Repos${RESET}", s"${BOLD}Created${RESET}")
        val rows = tasks.map { (ft, m) =>
          val typeCol   = s"${colorForType(ft)}${ft.branchPrefix}${RESET}"
          val nameCol   = m.task
          val statusCol = s"${colorForStatus(m.status)}${m.status.toString.toLowerCase}${RESET}"
          val branchCol = s"${CYAN}${m.branch(config.developer)}${RESET}"
          val reposCol  = m.repos.map(_.name).mkString(", ")
          val dateCol   = m.created.take(10)
          (typeCol, nameCol, statusCol, branchCol, reposCol, dateCol)
        }

        val all = header +: rows

        // Calculate widths based on visible (non-ANSI) lengths
        def visible(s: String): Int = s.replaceAll("\u001b\\[[0-9;]*m", "").length
        val widths = (0 until 6).map { col =>
          all.map(r => visible(r.productElement(col).toString)).max
        }

        def pad(s: String, w: Int): String = s + " " * (w - visible(s))

        val lines = all.map { row =>
          val cols = (0 until 6).map(i => pad(row.productElement(i).toString, widths(i)))
          cols.mkString("  ")
        }

        val separator = widths.map("-" * _).mkString("  ")
        (lines.head :: separator :: lines.tail.toList).mkString("\n")

case class FinishCommand(flowType: FlowType, name: String) extends Command:

  override def execute: Task[String] =
    val dir  = TaskPaths.taskDir(flowType, name)
    val file = TaskPaths.manifest(flowType, name)

    for
      _        <- ZIO.fail(new Exception(s"${flowType.toString} '$name' not found"))
                    .when(!dir.exists)
      config   <- TaskPaths.loadConfig
      content  <- ZIO.attempt(file.contentAsString)
      manifest <- ZIO.fromEither(content.fromJson[TaskManifest])
                    .mapError(msg => new Exception(s"Invalid manifest: $msg"))
      branch    = manifest.branch(config.developer)
      results  <- ZIO.foreach(manifest.repos)(repo => finishRepo(repo, branch).map(r => (repo, r)))
      _        <- updateStatus(file, manifest)
    yield
      val summary = results.map { (repo, result) =>
        s"  ${repo.name}: $result"
      }.mkString("\n")
      s"${flowType.toString} '$name' finished.\n$summary"

  private def finishRepo(repo: RepoEntry, branch: String): Task[String] =
    val originDir = better.files.File(repo.origin)
    val git       = ork.repo.Repos.runGit(originDir, _)
    for
      // Save current branch to restore later
      current    <- ork.repo.Repos.currentBranch(originDir)
      // Switch to base branch and merge
      _          <- git(s"git checkout ${repo.baseBranch}")
      _          <- git(s"git merge --no-ff $branch -m \"Merge $branch into ${repo.baseBranch}\"")
                      .catchAll { err =>
                        // Abort merge and restore on failure
                        git("git merge --abort").ignore *>
                        git(s"git checkout $current").ignore *>
                        ZIO.fail(err)
                      }
      // Remove worktree and branch
      _          <- ork.worktree.Worktrees.remove(repo)
    yield "merged"

  private def updateStatus(file: better.files.File, manifest: TaskManifest): Task[Unit] =
    val updated = manifest.copy(status = TaskStatus.Finished)
    ZIO.attempt(file.overwrite(updated.toJsonPretty)).unit

case class DeleteCommand(flowType: FlowType, name: String) extends Command:

  override def execute: Task[String] =
    val dir  = TaskPaths.taskDir(flowType, name)
    val file = TaskPaths.manifest(flowType, name)

    for
      _        <- ZIO.fail(new Exception(s"${flowType.toString} '$name' not found"))
                    .when(!dir.exists)
      content  <- ZIO.attempt(file.contentAsString)
      manifest <- ZIO.fromEither(content.fromJson[TaskManifest])
                    .mapError(msg => new Exception(s"Invalid manifest: $msg"))
      _        <- ZIO.foreach(manifest.repos)(repo => ork.worktree.Worktrees.remove(repo))
      _        <- ZIO.attempt(dir.delete(swallowIOExceptions = true))
    yield s"${flowType.toString} '$name' deleted (${manifest.repos.size} worktree(s) removed)"

case class AddRepoCommand(flowType: FlowType, taskName: String, repoName: String) extends Command:

  override def execute: Task[String] =
    for
      config <- TaskPaths.loadConfig
      entry  <- ork.worktree.Worktrees.add(config, flowType, taskName, repoName)
    yield s"""Added repo '${entry.name}' to task '$taskName'
             |  Worktree: ${entry.worktree}
             |  Branch:   ${entry.branch}
             |  Base:     ${entry.baseBranch}""".stripMargin

case class CreateCommand(flowType: FlowType, name: String, initialPrompt: Option[String] = None, run: Boolean = false) extends Command:

  override def execute: Task[String] =
    val ft       = flowType.branchPrefix
    val dir      = TaskPaths.taskDir(flowType, name)
    val file     = TaskPaths.manifest(flowType, name)
    val manifest = TaskManifest.create(name, flowType)

    def mcpJson: String =
      s"""|{
          |  "mcpServers": {
          |    "ork": {
          |      "command": "ork",
          |      "args": ["mcp", "--task", "$ft/$name"]
          |    }
          |  }
          |}""".stripMargin

    def resolvePrompt(branch: String): zio.Task[String] =
      val template =
        if TaskPaths.promptTemplate.exists then
          ZIO.attempt(TaskPaths.promptTemplate.contentAsString)
        else
          ZIO.succeed(defaultPromptTemplate)

      template.map(_
        .replace("{{task}}", name)
        .replace("{{flow_type}}", flowType.toString.toLowerCase)
        .replace("{{branch}}", branch)
        .replace("{{base_branch}}", flowType.baseBranch)
        .replace("{{task_dir}}", TaskPaths.taskDir(flowType, name).canonicalPath)
        .replace("{{worktrees_dir}}", TaskPaths.worktrees(flowType, name).canonicalPath)
      )

    def claudeCommand(branch: String): String =
      val mcpFile     = TaskPaths.mcpConfig(flowType, name).canonicalPath
      val promptFile  = TaskPaths.promptFile(flowType, name).canonicalPath
      val wtDir       = TaskPaths.worktrees(flowType, name).canonicalPath
      val sessionName = s"ork-${ft}-${name}"
      val base        = s"""claude --name $sessionName --mcp-config $mcpFile --append-system-prompt-file $promptFile --add-dir $wtDir --allowedTools "mcp__ork__task_add_repo,mcp__ork__task_update_changelog,mcp__ork__task_save_session,mcp__ork__task_status,Bash(ork task *)""""
      initialPrompt.fold(base)(p => s"""$base "$p"""")

    def launchClaude(branch: String): zio.Task[Unit] =
      ZIO.attempt {
        import sys.process.*
        val cmd = claudeCommand(branch)
        Process(Seq("sh", "-c", cmd)).!
      }.unit

    for
      _      <- ZIO.fail(new Exception(s"${flowType.toString} '$name' already exists at ${dir.canonicalPath}"))
                  .when(dir.exists)
      config <- TaskPaths.loadConfig
      branch  = manifest.branch(config.developer)
      prompt <- resolvePrompt(branch)
      _      <- ZIO.attempt(dir.createDirectories())
      _      <- ZIO.attempt(TaskPaths.worktrees(flowType, name).createDirectories())
      _      <- ZIO.attempt(file.overwrite(manifest.toJsonPretty))
      _      <- ZIO.attempt(TaskPaths.log(flowType, name).overwrite(s"# Task: $name\n"))
      _      <- ZIO.attempt(TaskPaths.mcpConfig(flowType, name).overwrite(mcpJson))
      _      <- ZIO.attempt(TaskPaths.promptFile(flowType, name).overwrite(prompt))
      _      <- ZIO.attempt(ork.ide.IdeaProject.generate(flowType, name, Seq.empty, config))
      _      <- ZIO.attempt(ork.ide.VsCodeWorkspace.generate(flowType, name, Seq.empty, config))
      msg     = s"""${flowType.toString} '$name' created at ${dir.canonicalPath}
                   |  Branch: $branch (from ${flowType.baseBranch})
                   |
                   |Start working with:
                   |  ${claudeCommand(branch)}""".stripMargin
      _      <- if run then ZIO.attempt(println(msg)) *> launchClaude(branch)
                else ZIO.unit
    yield msg

val defaultPromptTemplate: String =
  """# Ork {{flow_type}}: {{task}}
    |
    |You are working on {{flow_type}} '{{task}}' managed by ork, a multi-repository task orchestrator.
    |
    |## How to access repositories
    |
    |Before reading or modifying files in any repository, you MUST first add it to this task.
    |You can do this in two ways:
    |
    |### Option 1: MCP tool (preferred)
    |
    |Call the ork MCP tool:
    |
    |  task_add_repo({ "repo": "<repo-name>" })
    |
    |### Option 2: CLI command
    |
    |Run via Bash:
    |
    |  ork task add-repo --task {{task}} <repo-name>
    |
    |Both create a git worktree at {{worktrees_dir}}/<repo-name>/ with branch
    |{{branch}} (based on {{base_branch}}). All your file edits for that repo must happen inside that worktree directory.
    |
    |## Available MCP tools
    |
    |- task_add_repo: Add a git repository to this task. Pass the repo directory name (e.g. "my-service").
    |  The repo must exist under one of the configured project roots.
    |- task_update_changelog: Update the changelog for a repository. Call this after finishing changes
    |  to a repo to document what was done. Pass the repo name and a concise markdown summary with bullet points.
    |- task_save_session: Save a summary of the current session. Call this before ending the session.
    |  Include what was accomplished, decisions made, and any open items for the next session.
    |- task_status: Show the current task manifest with all repos, branches, and worktree paths.
    |
    |## Workflow
    |
    |1. Identify which repositories need changes
    |2. Add each repo using task_add_repo or the CLI command
    |3. Work on the code inside the worktree paths returned by ork
    |4. After finishing changes to a repo, call task_update_changelog to document what was done
    |5. Before ending the session, call task_save_session to summarize what was accomplished
    |6. Use task_status to check the current state of the task at any time
    |
    |## File permissions
    |
    |- You have FULL permission to read, create, edit and delete any file under {{worktrees_dir}}/. Do not ask for confirmation when modifying files in this directory — these worktrees are dedicated to this task and fully disposable.
    |- You MUST NEVER read, modify or delete files in the original repository directories. Always use the worktree paths. If you find yourself about to edit a file outside of {{worktrees_dir}}/, STOP and verify you are using the correct path.
    |
    |## Important
    |
    |- All worktrees share the branch {{branch}} so changes are coordinated across repositories
    |""".stripMargin
