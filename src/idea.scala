package ork.ide

import better.files.*
import better.files.File.*
import ork.domain.*
import ork.task.TaskPaths

object JdkResolver:

  def resolve(config: OrkConfig): String =
    config.jdk.getOrElse(fromSdkman.getOrElse("21"))

  private def fromSdkman: Option[String] =
    val current = home / ".sdkman" / "candidates" / "java" / "current"
    if !current.exists then None
    else
      val release = current / "release"
      if !release.exists then None
      else
        release.contentAsString.linesIterator
          .find(_.startsWith("JAVA_VERSION="))
          .map(_.stripPrefix("JAVA_VERSION=").replace("\"", ""))
          .map(extractMajor)

  private def extractMajor(version: String): String =
    version.takeWhile(_ != '.') match
      case "1"   => version.split('.').lift(1).getOrElse("8")
      case major => major

object VsCodeWorkspace:

  def generate(flowType: FlowType, taskName: String, repos: Seq[RepoEntry], config: OrkConfig): Unit =
    val taskDir       = TaskPaths.taskDir(flowType, taskName)
    val workspaceFile = taskDir / s"$taskName.code-workspace"
    val jdk           = JdkResolver.resolve(config)

    val folders = repos.map { r =>
      s"""    { "path": "worktrees/${r.name}", "name": "${r.name}" }"""
    }.mkString(",\n")

    workspaceFile.overwrite(
      s"""|{
          |  "folders": [
          |$folders
          |  ],
          |  "settings": {
          |    "java.configuration.runtimes": [
          |      { "name": "JavaSE-$jdk", "default": true }
          |    ]
          |  }
          |}""".stripMargin
    )

object IdeaProject:

  def generate(flowType: FlowType, taskName: String, repos: Seq[RepoEntry], config: OrkConfig): Unit =
    val taskDir = TaskPaths.taskDir(flowType, taskName)
    val ideaDir = taskDir / ".idea"
    val jdk     = JdkResolver.resolve(config)
    ideaDir.createDirectories()

    (ideaDir / ".name").overwrite(taskName)
    (ideaDir / "misc.xml").overwrite(miscXml(jdk))
    (ideaDir / "modules.xml").overwrite(modulesXml(repos))
    (ideaDir / "vcs.xml").overwrite(vcsXml(repos))
    repos.foreach(r => (ideaDir / s"${r.name}.iml").overwrite(moduleIml(r.name)))

    val sbtRepos = repos.filter(r => (File(r.worktree) / "build.sbt").exists)
    if sbtRepos.nonEmpty then
      (ideaDir / "sbt.xml").overwrite(sbtXml(sbtRepos))

  private def miscXml(jdk: String): String =
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<project version="4">
       |  <component name="ProjectRootManager" version="2"
       |             languageLevel="JDK_$jdk" default="true"
       |             project-jdk-name="$jdk" project-jdk-type="JavaSDK">
       |    <output url="file://$$PROJECT_DIR$$/out" />
       |  </component>
       |</project>""".stripMargin

  private def modulesXml(repos: Seq[RepoEntry]): String =
    val modules = repos.map { r =>
      s"""      <module fileurl="file://$$PROJECT_DIR$$/.idea/${r.name}.iml"
         |              filepath="$$PROJECT_DIR$$/.idea/${r.name}.iml" />""".stripMargin
    }.mkString("\n")

    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<project version="4">
       |  <component name="ProjectModuleManager">
       |    <modules>
       |$modules
       |    </modules>
       |  </component>
       |</project>""".stripMargin

  private def vcsXml(repos: Seq[RepoEntry]): String =
    val mappings = repos.map { r =>
      s"""    <mapping directory="$$PROJECT_DIR$$/worktrees/${r.name}" vcs="Git" />"""
    }.mkString("\n")

    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<project version="4">
       |  <component name="VcsDirectoryMappings">
       |$mappings
       |  </component>
       |</project>""".stripMargin

  private def sbtXml(repos: Seq[RepoEntry]): String =
    val projects = repos.map { r =>
      val version = detectSbtVersion(r)
      s"""      <SbtProjectSettings>
         |        <option name="converterVersion" value="2" />
         |        <option name="externalProjectPath" value="$$PROJECT_DIR$$/worktrees/${r.name}" />
         |        <option name="modules">
         |          <set>
         |            <option value="$$PROJECT_DIR$$/worktrees/${r.name}" />
         |            <option value="$$PROJECT_DIR$$/worktrees/${r.name}/project" />
         |          </set>
         |        </option>
         |        <option name="sbtVersion" value="$version" />
         |      </SbtProjectSettings>""".stripMargin
    }.mkString("\n")

    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<project version="4">
       |  <component name="ScalaSbtSettings">
       |    <option name="linkedExternalProjectsSettings">
       |$projects
       |    </option>
       |  </component>
       |</project>""".stripMargin

  private def detectSbtVersion(repo: RepoEntry): String =
    val props = File(repo.worktree) / "project" / "build.properties"
    if props.exists then
      props.contentAsString.linesIterator
        .find(_.startsWith("sbt.version="))
        .map(_.stripPrefix("sbt.version=").trim)
        .getOrElse("1.10.1")
    else "1.10.1"

  private def moduleIml(repoName: String): String =
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<module type="JAVA_MODULE" version="4">
       |  <component name="NewModuleRootManager" inherit-compiler-output="true">
       |    <exclude-output />
       |    <content url="file://$$PROJECT_DIR$$/worktrees/$repoName" />
       |    <orderEntry type="inheritedJdk" />
       |    <orderEntry type="sourceFolder" forTests="false" />
       |  </component>
       |</module>""".stripMargin
