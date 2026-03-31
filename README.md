# Ork

A multi-repository task orchestrator for AI-assisted development. Ork manages git worktrees across multiple repositories following git flow conventions, enabling isolated, parallel work on tasks that span several codebases.

## The Problem

Modern development often requires changes across multiple repositories for a single feature or fix. When using AI coding agents (Claude Code, etc.), each session needs access to the right repos, on the right branches, without conflicts. Ork solves this by:

- Following git flow conventions (feature, hotfix, release)
- Creating isolated git worktrees per task, so parallel work doesn't collide
- Exposing an MCP server so AI agents can dynamically add repos mid-session
- Generating IDE project files (IntelliJ, VS Code) for multi-repo workspaces
- Keeping all worktrees for a task under a single directory

## How It Works

```
ork feature create my-feature "Add auth to API"
  │
  ├── Creates ~/.ork/tasks/feature/my-feature/
  │     ├── ork.json                (task manifest)
  │     ├── log.md                  (task log)
  │     ├── mcp.json                (MCP server config)
  │     ├── prompt.md               (system prompt for Claude)
  │     ├── my-feature.code-workspace (VS Code workspace)
  │     ├── .idea/                  (IntelliJ project)
  │     └── worktrees/              (git worktrees)
  │
  └── Prints the claude launch command

Claude session starts
  │
  ├── Claude realizes it needs repos r1 and r2
  │
  ├── Calls MCP tool: task_add_repo({ "repo": "r1" })
  │     ├── Finds r1 in configured project roots
  │     ├── Creates git worktree with branch feature/<developer>/my-feature
  │     ├── Regenerates IDE project files
  │     └── Updates ork.json manifest
  │
  ├── Works on code in the worktree directories
  ├── Calls task_update_changelog to document changes
  ├── Calls task_save_session to summarize the session
  │
  └── When done: ork feature finish my-feature
        ├── Merges feature branch into develop (--no-ff)
        ├── Removes worktrees and deletes branches
        └── Marks task as finished (keeps directory as archive)
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
scala-cli --power package --server=false project.scala -o ork -f --native-image -- --no-fallback
```

## Configuration

### Global config (`~/.ork/config.json`)

```json
{
  "developer": "leandro",
  "jdk": "21",
  "roots": [
    "/home/user/projects/backend",
    "/home/user/projects/frontend"
  ]
}
```

| Field | Description |
|-------|-------------|
| `developer` | Prepended to branch names: `feature/<developer>/<task>`. Optional. |
| `jdk` | JDK version for IDE project files. Falls back to sdkman's current, then `21`. Optional. |
| `roots` | Directories containing git repositories as immediate subdirectories. |

### Prompt template (`~/.ork/prompt.md`)

Customizable system prompt injected into Claude sessions. Supports placeholders: `{{task}}`, `{{flow_type}}`, `{{branch}}`, `{{base_branch}}`, `{{task_dir}}`, `{{worktrees_dir}}`.

## Usage

### Git flow commands

```bash
# Create a task (feature branches from develop, hotfix/release from master)
ork feature create my-feature
ork feature create my-feature "Add auth to the API"    # with initial prompt
ork feature create --run my-feature "Add auth"          # create and launch claude

ork hotfix create urgent-fix
ork release create v2.0

# Finish a task (merge --no-ff into base branch, remove worktrees)
ork feature finish my-feature
ork hotfix finish urgent-fix

# Delete a task (remove worktrees without merging)
ork feature delete my-feature
```

Task names can include a developer prefix: `ork feature create john/my-feature`.

### Task management

```bash
# List all tasks
ork list

# Show task details (repos, commits, stats, changelog, sessions)
ork info my-feature

# Add a repo to a task (also available as MCP tool)
ork task add-repo --task feature/my-feature my-service

# Resume a Claude session
ork resume my-feature

# Open in IDE
ork open my-feature              # IntelliJ (default)
ork open --idea my-feature       # IntelliJ
ork open --code my-feature       # VS Code
```

### Work with Claude

The `create` command prints the full Claude launch command:

```bash
claude --name ork-feature-my-feature \
       --mcp-config ~/.ork/tasks/feature/my-feature/mcp.json \
       --append-system-prompt-file ~/.ork/tasks/feature/my-feature/prompt.md \
       --add-dir ~/.ork/tasks/feature/my-feature/worktrees \
       --allowedTools "mcp__ork__task_add_repo,mcp__ork__task_update_changelog,mcp__ork__task_save_session,mcp__ork__task_status,Bash(ork task *)"
```

### MCP tools

Claude has access to these tools during a session:

| Tool | Description |
|------|-------------|
| `task_add_repo` | Add a repository to the task. Creates a git worktree with the appropriate branch. |
| `task_update_changelog` | Document changes made to a repo (markdown summary with bullet points). |
| `task_save_session` | Save a session summary (what was done, decisions, open items). |
| `task_status` | Show current task manifest with all repos, branches, and worktree paths. |

## Task Directory Structure

```
~/.ork/
  config.json                              # global config
  prompt.md                                # prompt template
  tasks/
    feature/
      my-feature/
        ork.json                           # task manifest
        log.md                             # task log
        mcp.json                           # MCP server config
        prompt.md                          # resolved prompt for Claude
        my-feature.code-workspace          # VS Code workspace
        .idea/                             # IntelliJ project
        worktrees/
          r1/                              # git worktree (branch: feature/leandro/my-feature)
          r2/                              # git worktree (branch: feature/leandro/my-feature)
        changelog/
          r1.md                            # per-repo changelog
          r2.md
        sessions/
          a1b2c3d4.md                      # session summaries
    hotfix/
      ...
    release/
      ...
```

## Task Manifest (`ork.json`)

```json
{
  "task": "my-feature",
  "flowType": "feature",
  "description": "",
  "created": "2026-03-28T10:30:00Z",
  "status": "active",
  "repos": [
    {
      "name": "r1",
      "origin": "/home/user/projects/backend/r1",
      "worktree": "/home/user/.ork/tasks/feature/my-feature/worktrees/r1",
      "branch": "feature/leandro/my-feature",
      "baseBranch": "develop",
      "baseCommit": "a1b2c3d"
    }
  ],
  "sessions": [
    { "id": "c767b0d7", "date": "2026-03-28T10:35:00Z" }
  ]
}
```

## Architecture

Ork is built with Scala 3 and ZIO, compiled to a native binary via GraalVM.

```
src/
  domain.scala     # Core types: FlowType, OrkConfig, TaskManifest, RepoEntry
  task.scala        # Task commands: create, delete, finish, list, info, open, resume
  repo.scala        # Repository resolution (scans configured roots)
  worktree.scala    # Git worktree lifecycle (create, remove)
  mcp.scala         # MCP server (JSON-RPC 2.0 over stdio)
  idea.scala         # IDE project generation (IntelliJ .idea, VS Code .code-workspace)
  app.scala         # CLI routing (mainargs)
  ork.scala         # ZIO entry point
```

### Integration Points

| Protocol | Role | Use Case |
|----------|------|----------|
| **MCP** (Model Context Protocol) | Server | AI agents call ork tools to add repos, update changelogs, save sessions |
| **Git worktrees** | Isolation | Each task/repo gets its own working directory and branch |
| **Git flow** | Convention | Feature from develop, hotfix/release from master, merge with --no-ff |
| **IntelliJ** | IDE | Generated `.idea` project with modules and VCS roots per repo |
| **VS Code** | IDE | Generated `.code-workspace` with folders per repo |
| **Claude Code** | Agent | System prompt, MCP config, `--add-dir`, `--allowedTools`, `--name` for resume |
