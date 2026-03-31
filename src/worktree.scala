package ork.worktree

import zio.*
import zio.json.*
import better.files.*
import better.files.File.*
import ork.domain.*
import ork.repo.Repos
import ork.task.TaskPaths

object Worktrees:

  def add(config: OrkConfig, flowType: FlowType, taskName: String, repoName: String): Task[RepoEntry] =
    val worktreeDir = TaskPaths.worktrees(flowType, taskName) / repoName

    for
      _          <- ZIO.fail(new Exception(s"Worktree for '$repoName' already exists in task '$taskName'"))
                      .when(worktreeDir.exists)
      manifest   <- loadManifest(flowType, taskName)
      repoDir    <- Repos.find(config, repoName)
      branch      = manifest.branch(config.developer)
      baseBranch  = manifest.flowType.baseBranch
      _          <- Repos.hasBranch(repoDir, baseBranch).flatMap {
                      case true  => ZIO.unit
                      case false => ZIO.fail(new Exception(
                        s"Repository '$repoName' does not have a '$baseBranch' branch. " +
                        s"Cannot create ${manifest.flowType.toString.toLowerCase} worktree."
                      ))
                    }
      baseCommit <- Repos.runGit(repoDir, s"git rev-parse --short $baseBranch").map(_.trim)
      hasBranch  <- Repos.hasBranch(repoDir, branch)
      _          <- if hasBranch
                    then Repos.runGit(repoDir, s"git worktree add ${worktreeDir.canonicalPath} $branch")
                    else Repos.runGit(repoDir, s"git worktree add -b $branch ${worktreeDir.canonicalPath} $baseBranch")
      entry       = RepoEntry(
                      name       = repoName,
                      origin     = repoDir.canonicalPath,
                      worktree   = worktreeDir.canonicalPath,
                      branch     = branch,
                      baseBranch = baseBranch,
                      baseCommit = baseCommit
                    )
      updated    <- updateManifest(flowType, taskName, manifest, entry)
      _          <- ZIO.attempt(ork.ide.IdeaProject.generate(flowType, taskName, updated.repos, config))
      _          <- ZIO.attempt(ork.ide.VsCodeWorkspace.generate(flowType, taskName, updated.repos, config))
    yield entry

  def remove(repo: RepoEntry): Task[Unit] =
    val originDir = File(repo.origin)
    for
      _ <- Repos.runGit(originDir, s"git worktree remove ${repo.worktree} --force")
             .catchAll(_ => ZIO.attempt(File(repo.worktree).delete(swallowIOExceptions = true)).unit)
      _ <- Repos.runGit(originDir, s"git branch -D ${repo.branch}")
             .catchAll(_ => ZIO.unit)
    yield ()

  private def loadManifest(flowType: FlowType, taskName: String): Task[TaskManifest] =
    val file = TaskPaths.manifest(flowType, taskName)
    for
      content  <- ZIO.attempt(file.contentAsString)
      manifest <- ZIO.fromEither(content.fromJson[TaskManifest])
                    .mapError(msg => new Exception(s"Invalid manifest: $msg"))
    yield manifest

  private def updateManifest(flowType: FlowType, taskName: String, manifest: TaskManifest, entry: RepoEntry): Task[TaskManifest] =
    val file    = TaskPaths.manifest(flowType, taskName)
    val updated = manifest.copy(repos = manifest.repos :+ entry)
    ZIO.attempt(file.overwrite(updated.toJsonPretty)).as(updated)
