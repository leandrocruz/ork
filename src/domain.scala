package ork.domain

import zio.json.*
import java.time.Instant

enum FlowType(val branchPrefix: String, val baseBranch: String):
  case Feature extends FlowType("feature", "develop")
  case Hotfix  extends FlowType("hotfix",  "master")
  case Release extends FlowType("release", "master")

object FlowType:
  given JsonEncoder[FlowType] = JsonEncoder[String].contramap(_.toString.toLowerCase)
  given JsonDecoder[FlowType] = JsonDecoder[String].map(s => FlowType.valueOf(s.capitalize))

  def fromString(s: String): Option[FlowType] =
    s match
      case "feature" => Some(Feature)
      case "hotfix"  => Some(Hotfix)
      case "release" => Some(Release)
      case _         => None

enum TaskStatus:
  case Active, Paused, Finished, Aborted

object TaskStatus:
  given JsonEncoder[TaskStatus] = JsonEncoder[String].contramap(_.toString.toLowerCase)
  given JsonDecoder[TaskStatus] = JsonDecoder[String].map(s => TaskStatus.valueOf(s.capitalize))

case class RepoEntry(
  name       : String,
  origin     : String,
  worktree   : String,
  branch     : String,
  baseBranch : String,
  baseCommit : String
) derives JsonCodec

case class SessionEntry(
  id   : String,
  date : String
) derives JsonCodec

case class TaskManifest(
  task        : String,
  flowType    : FlowType,
  description : String,
  created     : String,
  status      : TaskStatus,
  repos       : Seq[RepoEntry],
  sessions    : Seq[SessionEntry]
) derives JsonCodec:
  def branch(developer: Option[String]): String =
    developer match
      case Some(dev) => s"${flowType.branchPrefix}/$dev/$task"
      case None      => s"${flowType.branchPrefix}/$task"

object TaskManifest:
  def create(name: String, flowType: FlowType, description: String = ""): TaskManifest =
    TaskManifest(
      task        = name,
      flowType    = flowType,
      description = description,
      created     = Instant.now().toString,
      status      = TaskStatus.Active,
      repos       = Seq.empty,
      sessions    = Seq.empty
    )

case class OrkConfig(
  developer : Option[String] = None,
  roots     : Seq[String]
) derives JsonCodec

object OrkConfig:
  val empty: OrkConfig = OrkConfig(developer = None, roots = Seq.empty)

trait Command:
  def execute: zio.Task[String]
