package ork.repo

import zio.*
import zio.json.*
import better.files.*
import better.files.File.*
import ork.domain.*
import scala.util.{Try, Success, Failure}

object Repos:

  def find(config: OrkConfig, name: String): Task[File] =
    for
      candidates <- ZIO.attempt {
                      config.roots
                        .map(File(_))
                        .filter(_.exists)
                        .flatMap(root =>
                          root.list
                            .filter(_.isDirectory)
                            .filter(_.name == name)
                            .filter(dir => (dir / ".git").exists)
                            .toSeq
                        )
                    }
      repo       <- candidates match
                      case Seq()     => ZIO.fail(new Exception(s"Repository '$name' not found in configured roots"))
                      case Seq(one)  => ZIO.succeed(one)
                      case multiple  => ZIO.fail(new Exception(
                                          s"Repository '$name' found in multiple locations:\n${multiple.map(_.canonicalPath).mkString("\n")}"
                                        ))
    yield repo

  def currentBranch(repoDir: File): Task[String] =
    runGit(repoDir, "git rev-parse --abbrev-ref HEAD").map(_.trim)

  def hasBranch(repoDir: File, branch: String): Task[Boolean] =
    runGit(repoDir, s"git rev-parse --verify $branch")
      .map(_ => true)
      .catchAll(_ => ZIO.succeed(false))

  def runGit(dir: File, cmd: String): Task[String] =
    ZIO.attempt {
      import sys.process.*
      val sb = new StringBuffer
      val logger = ProcessLogger(
        out => sb.append(out).append("\n"),
        err => ()
      )
      val code = Process(cmd, dir.toJava) !< logger
      if code != 0 then throw new Exception(s"'$cmd' failed at '$dir' (exit $code): ${sb.toString}")
      sb.toString
    }
