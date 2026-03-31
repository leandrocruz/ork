# Changes

## Unreleased

### Added

- Git flow commands: `ork feature create`, `ork hotfix create`, `ork release create` with branching from the correct base branch (develop/master)
- `ork feature finish` / `ork hotfix finish` / `ork release finish` — merges branch into base with `--no-ff` (git flow style), removes worktrees, marks task as finished
- `ork feature delete` / `ork hotfix delete` / `ork release delete` — removes worktrees and task directory without merging
- `ork list` — colored tabular display of all tasks across flow types
- `ork info <name>` — detailed task view with repos, commits, stats, changelogs, and sessions
- `ork open <name>` — open task in IntelliJ (`--idea`, default) or VS Code (`--code`)
- `ork resume <name>` — resume a Claude session with full MCP/prompt/worktree config
- `ork task add-repo --task <flow/name> <repo>` — add a repository to a task via CLI
- MCP server with tools: `task_add_repo`, `task_update_changelog`, `task_save_session`, `task_status`
- System prompt template (`~/.ork/prompt.md`) with placeholder support, instructs Claude on ork workflow and file permissions
- Auto-generated IntelliJ `.idea` project and VS Code `.code-workspace` files, regenerated on each repo add
- JDK resolution for IDE files: from `~/.ork/config.json`, sdkman, or fallback to 21
- Developer name from config prepended to branch names (`feature/<developer>/<task>`)
- Base branch validation when adding repos (fails if repo lacks the required branch)
- Reuse existing branch when creating worktrees (handles re-adding after failed attempts)
- Session tracking: MCP server records sessions in `ork.json` on each Claude connection
- Per-repo changelogs (`changelog/<repo>.md`) written by Claude via MCP tool
- Per-session summaries (`sessions/<id>.md`) written by Claude via MCP tool
- `--run` flag on create to launch Claude immediately
- Initial prompt support: `ork feature create my-task "do something"`
- `--add-dir` for worktrees directory in Claude launch command
- `--name` for Claude session naming, enabling `ork resume`
- `--allowedTools` pre-approves all ork MCP tools and CLI commands
- CLI parsing via mainargs with `--help` support
- `baseCommit` stored in manifest for each repo (HEAD of base branch at worktree creation)
