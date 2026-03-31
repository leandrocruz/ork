package ork.app

import zio.*
import zio.Console.*
import ork.domain.*
import ork.task.*
import ork.mcp.{McpServer, TaskRef}

object Ork:

  def run(args: Seq[String]): Task[Unit] =
    args.toList match
      case "mcp" :: rest => runMcp(rest)
      case other         => runCommand(other)

  private def runMcp(args: List[String]): Task[Unit] =
    args match
      case "--task" :: name :: Nil => McpServer.run(name)
      case _                      => ZIO.fail(new Exception("Usage: ork mcp --task <name>"))

  private def runCommand(args: List[String]): Task[Unit] =

    def parseCommand(args: List[String]): Task[Command] =
      val hasRun  = args.contains("--run")
      val cleaned = args.filterNot(_ == "--run")

      cleaned match
        case flow :: "create" :: name :: prompt :: Nil if isFlow(flow) => ZIO.succeed(CreateCommand(toFlow(flow), name, Some(prompt), run = hasRun))
        case flow :: "create" :: name :: Nil           if isFlow(flow) => ZIO.succeed(CreateCommand(toFlow(flow), name, run = hasRun))
        case flow :: "create" :: Nil                   if isFlow(flow) => ZIO.fail(new Exception(s"Usage: ork $flow create [--run] <name> [prompt]"))
        case flow :: "delete" :: name :: Nil           if isFlow(flow) => ZIO.succeed(DeleteCommand(toFlow(flow), name))
        case flow :: "delete" :: Nil                   if isFlow(flow) => ZIO.fail(new Exception(s"Usage: ork $flow delete <name>"))
        case "task" :: "add-repo" :: "--task" :: task :: repo :: Nil   => parseTaskRef(task).map(ref => AddRepoCommand(ref.flowType, ref.name, repo))
        case "task" :: "add-repo" :: _                                 => ZIO.fail(new Exception("Usage: ork task add-repo --task <flow/name> <repo>"))
        case "list" :: Nil                                             => ZIO.succeed(ListCommand())
        case "info" :: name :: Nil                                     => ZIO.succeed(InfoCommand(name))
        case "info" :: Nil                                             => ZIO.fail(new Exception("Usage: ork info <name>"))
        case "resume" :: name :: Nil                                   => ZIO.succeed(ResumeCommand(name))
        case "resume" :: Nil                                           => ZIO.fail(new Exception("Usage: ork resume <name>"))
        case cmd :: _                                                  => ZIO.fail(new Exception(s"Unknown command: '$cmd'. Available: feature, hotfix, release, list, task"))
        case Nil                                                       => ZIO.fail(new Exception("Usage: ork <feature|hotfix|release> create [--run] <name> [prompt]"))

    for
      command <- parseCommand(args)
      result  <- command.execute
      _       <- printLine(result)
    yield ()

  private def isFlow(s: String): Boolean    = FlowType.fromString(s).isDefined
  private def toFlow(s: String): FlowType  = FlowType.fromString(s).get
  private def parseTaskRef(s: String): Task[TaskRef] =
    ZIO.fromEither(TaskRef.parse(s)).mapError(msg => new Exception(msg))
