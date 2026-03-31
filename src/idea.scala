package ork.ide

import better.files.*
import ork.domain.*
import ork.task.TaskPaths

object VsCodeWorkspace:

  def generate(flowType: FlowType, taskName: String, repos: Seq[RepoEntry]): Unit =
    val taskDir       = TaskPaths.taskDir(flowType, taskName)
    val workspaceFile = taskDir / s"$taskName.code-workspace"

    val folders = repos.map { r =>
      s"""    { "path": "worktrees/${r.name}", "name": "${r.name}" }"""
    }.mkString(",\n")

    workspaceFile.overwrite(
      s"""|{
          |  "folders": [
          |$folders
          |  ],
          |  "settings": {}
          |}""".stripMargin
    )

object IdeaProject:

  def generate(flowType: FlowType, taskName: String, repos: Seq[RepoEntry]): Unit =
    val taskDir = TaskPaths.taskDir(flowType, taskName)
    val ideaDir = taskDir / ".idea"
    ideaDir.createDirectories()

    (ideaDir / ".name").overwrite(taskName)
    (ideaDir / "misc.xml").overwrite(miscXml)
    (ideaDir / "modules.xml").overwrite(modulesXml(repos))
    (ideaDir / "vcs.xml").overwrite(vcsXml(repos))
    repos.foreach(r => (ideaDir / s"${r.name}.iml").overwrite(moduleIml(r.name)))

  private def miscXml: String =
    s"""<?xml version="1.0" encoding="UTF-8"?>
       |<project version="4">
       |  <component name="ProjectRootManager" version="2"
       |             languageLevel="JDK_21" default="true"
       |             project-jdk-name="21" project-jdk-type="JavaSDK">
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
