# Ork

A multi-repository task orchestrator for AI-assisted development. Ork manages git worktrees across multiple repositories, enabling isolated, parallel work on tasks that span several codebases.

## The Problem

Modern development often requires changes across multiple repositories for a single feature or fix. When using AI coding agents (Claude Code, etc.), each session needs access to the right repos, on the right branches, without conflicts. Ork solves this by:

- Creating isolated git worktrees per task, so parallel work doesn't collide
- Exposing an MCP server so AI agents can dynamically add repos mid-session
- Keeping all worktrees for a task under a single directory

## How It Works

```
ork task start my-feature
  │
  ├── Creates ~/.ork/tasks/my-feature/
  │     ├── ork.json       (task manifest)
  │     ├── log.md         (task log)
  │     └── mcp.json       (MCP server config for Claude)
  │
  └── Prints: claude --mcp-config ~/.ork/tasks/my-feature/mcp.json

Claude session starts
  │
  ├── Claude realizes it needs repos r1 and r2
  │
  ├── Calls MCP tool: task_add_repo({ "repo": "r1" })
  │     ├── Finds r1 in configured project roots
  │     ├── Creates git worktree at ~/.ork/tasks/my-feature/worktrees/r1/
  │     ├── Branch: feature/my-feature (based on current branch of r1)
  │     └── Updates ork.json manifest
  │
  ├── Calls MCP tool: task_add_repo({ "repo": "r2" })
  │     └── Same as above for r2
  │
  └── Claude now works across both worktrees
```

## Installation

### Prerequisites

- [Scala CLI](https://scala-cli.virtuslab.org/)
- [GraalVM 24+](https://www.graalvm.org/) (for native compilation)
- Git

### Compile

```bash
scala-cli compile --server=false project.scala
```

### Native binary

```bash
scala-cli --power package --server=false project.scala -o ork --native-image -- --no-fallback
```

## Configuration

### Global config (`~/.ork/config.json`)

Define the directories where your git repositories live:

```json
{
  "roots": [
    "/home/user/projects/backend",
    "/home/user/projects/frontend",
    "/home/user/projects/shared"
  ]
}
```

Ork scans these roots to find repositories by name. Each root is a directory containing git repositories as subdirectories.

## Usage

### Start a task

```bash
ork task start my-feature
```

Creates the task directory and prints the Claude launch command.

### Work with Claude

```bash
claude --mcp-config ~/.ork/tasks/my-feature/mcp.json
```

Claude can then use the following MCP tools during the session:

| Tool | Description |
|------|-------------|
| `task_add_repo` | Add a repository to the task. Creates a git worktree under the task directory with a `feature/<task>` branch. |
| `task_status` | Show current task manifest (repos, branches, worktree paths). |

### Work with IntelliJ

Open the task's worktrees directory as a project, or add individual worktrees as modules:

1. Open `~/.ork/tasks/my-feature/worktrees/r1/`
2. File | New | Module from Existing Sources → `~/.ork/tasks/my-feature/worktrees/r2/`

IntelliJ detects both VCS roots and offers synchronized branch operations since both are on `feature/my-feature`.

## Task Directory Structure

```
~/.ork/
  config.json                          # global config (project roots)
  tasks/
    my-feature/
      ork.json                         # task manifest
      log.md                           # task log
      mcp.json                         # MCP server config
      worktrees/
        r1/                            # git worktree (branch: feature/my-feature)
        r2/                            # git worktree (branch: feature/my-feature)
```

## Task Manifest (`ork.json`)

```json
{
  "task": "my-feature",
  "description": "",
  "created": "2026-03-28T10:30:00Z",
  "status": "active",
  "repos": [
    {
      "name": "r1",
      "origin": "/home/user/projects/backend/r1",
      "worktree": "/home/user/.ork/tasks/my-feature/worktrees/r1",
      "branch": "feature/my-feature",
      "baseBranch": "develop"
    }
  ],
  "sessions": []
}
```

## Architecture

Ork is built with Scala 3 and ZIO, compiled to a native binary via GraalVM.

```
src/
  domain.scala     # Core types: OrkConfig, TaskManifest, RepoEntry
  task.scala        # Task paths and the 'start' command
  repo.scala        # Repository resolution (scans configured roots)
  worktree.scala    # Git worktree lifecycle (create, update manifest)
  mcp.scala         # MCP server (JSON-RPC 2.0 over stdio)
  app.scala         # CLI routing
  ork.scala         # Entry point
```

### Integration Points

| Protocol | Role | Use Case |
|----------|------|----------|
| **MCP** (Model Context Protocol) | Server | AI agents call ork tools to add repos, check status |
| **Git worktrees** | Isolation | Each task/repo gets its own working directory and branch |
| **ACP** (Agent Client Protocol) | Planned | Register ork in JetBrains Air / IDEs |
