package ork.app

import zio.*
import zio.Console.*
import mainargs.{main as cmd, arg, Flag, ParserForMethods, ParserForClass}
import ork.domain.*
import ork.task.*
import ork.mcp.{McpServer, TaskRef}

object FlowCommands:
  @cmd(doc = "Create a new task")
  def create(
    @arg(positional = true, doc = "Task name")      name   : String,
    @arg(positional = true, doc = "Initial prompt")  prompt : String = "",
    @arg(doc = "Launch claude immediately")           run    : Flag
  ): (String, String, Boolean) = (name, prompt, run.value)

  @cmd(doc = "Finish a task (merge into base branch)")
  def finish(
    @arg(positional = true, doc = "Task name") name : String
  ): (String, String) = ("finish", name)

  @cmd(doc = "Delete a task")
  def delete(
    @arg(positional = true, doc = "Task name") name : String
  ): (String, String) = ("delete", name)

  @cmd(doc = "Export a task for transfer to another host")
  def `export`(
    @arg(positional = true, doc = "Task name") name : String
  ): (String, String) = ("export", name)

object TopCommands:
  @cmd(doc = "List all tasks")
  def list(): String = "list"

  @cmd(doc = "Show task details")
  def info(
    @arg(positional = true, doc = "Task name") name : String
  ): (String, String) = ("info", name)

  @cmd(doc = "Resume a claude session")
  def resume(
    @arg(positional = true, doc = "Task name") name : String
  ): (String, String) = ("resume", name)

  @cmd(doc = "Open task in an IDE")
  def open(
    @arg(positional = true, doc = "Task name") name : String,
    @arg(doc = "Open in IntelliJ IDEA")         idea : Flag,
    @arg(doc = "Open in VS Code")               code : Flag
  ): (String, String, Boolean, Boolean) = ("open", name, idea.value, code.value)

  @cmd(doc = "Import a task from an export file")
  def `import`(
    @arg(positional = true, doc = "Path to .ork-task.json file") file : String
  ): (String, String) = ("import", file)

@cmd case class TaskAddRepoArgs(
  @arg(doc = "Task identifier (flow/name)")      task : String,
  @arg(positional = true, doc = "Repo name")     repo : String
)

@cmd case class McpArgs(
  @arg(doc = "Task identifier (flow/name)") task : String
)

object Ork:

  def run(args: Seq[String]): Task[Unit] =
    args.toList match
      case "mcp" :: rest                          => runMcp(rest)
      case flow :: rest if isFlow(flow)           => runFlow(toFlow(flow), rest)
      case "task" :: "add-repo" :: rest           => runAddRepo(rest.toArray)
      case other                                  => runTop(other.toArray)

  private def runMcp(args: List[String]): Task[Unit] =
    val parsed = ParserForClass[McpArgs].constructOrThrow(args.toArray)
    McpServer.run(parsed.task)

  private def runFlow(flowType: FlowType, args: List[String]): Task[Unit] =
    val command = ParserForMethods(FlowCommands).runOrThrow(args.toArray)
    val cmd = command match
      case (name: String, prompt: String, run: Boolean) =>
        val p = if prompt.isEmpty then None else Some(prompt)
        CreateCommand(flowType, name, p, run)
      case ("finish", name: String) =>
        FinishCommand(flowType, name)
      case ("delete", name: String) =>
        DeleteCommand(flowType, name)
      case ("export", name: String) =>
        ExportCommand(flowType, name)
    for
      result <- cmd.execute
      _      <- printLine(result)
    yield ()

  private def runAddRepo(args: Array[String]): Task[Unit] =
    val parsed = ParserForClass[TaskAddRepoArgs].constructOrThrow(args)
    for
      ref    <- ZIO.fromEither(TaskRef.parse(parsed.task)).mapError(msg => new Exception(msg))
      cmd     = AddRepoCommand(ref.flowType, ref.name, parsed.repo)
      result <- cmd.execute
      _      <- printLine(result)
    yield ()

  private def runTop(args: Array[String]): Task[Unit] =
    val command = ParserForMethods(TopCommands).runOrThrow(args)
    val cmd: Command = command match
      case "list"                                             => ListCommand()
      case ("info", name: String)                             => InfoCommand(name)
      case ("resume", name: String)                           => ResumeCommand(name)
      case ("open", name: String, idea: Boolean, code: Boolean) =>
        val ide = if code then Ide.VsCode else Ide.IntelliJ
        OpenCommand(name, ide)
      case ("import", file: String) =>
        ImportCommand(file)
    for
      result <- cmd.execute
      _      <- printLine(result)
    yield ()

  private def isFlow(s: String): Boolean   = FlowType.fromString(s).isDefined
  private def toFlow(s: String): FlowType  = FlowType.fromString(s).get
