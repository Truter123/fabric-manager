# Claude settings window

Browse and edit Claude Code configuration for the current tab's project and for `~/.claude`.
Source: code.claude.com/docs/en/ (settings, skills, sub-agents, hooks, mcp, memory), read 2026-10-11.

## What Claude Code reads (the facts the window shows)

| Kind | User (`~/.claude`) | Project (shared) | Project (local) |
|---|---|---|---|
| Settings | `settings.json` | `.claude/settings.json` | `.claude/settings.local.json` |
| Memory | `CLAUDE.md`, `rules/*.md` | `CLAUDE.md` or `.claude/CLAUDE.md`, `.claude/rules/**/*.md` | `CLAUDE.local.md` |
| Skills | `skills/<name>/SKILL.md` | `.claude/skills/<name>/SKILL.md` | — |
| Agents | `agents/*.md` | `.claude/agents/*.md` | — |
| Commands | `commands/*.md` | `.claude/commands/*.md` | — |
| Output styles | `output-styles/*.md` | `.claude/output-styles/*.md` | — |
| MCP | `~/.claude.json` (read-only here) | `.mcp.json` | `~/.claude.json` (read-only here) |
| Keybindings | `keybindings.json` | — | — |

Hooks and `statusLine` are keys inside the settings files. Managed files (`/etc/claude-code/`) are shown read-only.

Precedence:
- Settings: managed > project local > project shared > user (key by key).
- Skills: user beats project; a skill beats a same-named command.
- Agents: project beats user.
- MCP: local > project > user.
- CLAUDE.md and hooks: all merged, nothing overridden.

Never written by the app: `~/.claude.json` and `~/.claude/plugins/` (Claude's own state; use `claude plugin`/`claude mcp`).

Live reload: settings, skills, agents and keybindings are reloaded by a running Claude. Output styles need a restart.

## Stages

### Stage 1: model (`ClaudeConfig`) — done 2026-10-12
- A pure Java scanner, no JavaFX: `scan(Path project, Path home)` returns a tree Scope → Kind → Item(path, name, description, readOnly).
- Front-matter reader for `name` / `description`.
- Done when: JUnit tests on a temp dir cover every row of the table above. `./gradlew test` is green.

### Stage 2: window (`ClaudeSettingsWindow`) — done 2026-10-12
- Copy the `HistoryWindow` pattern: one window per project, a TreeView on the left and a TextArea editor on the right.
- Keys: Ctrl+S saves, F5 reloads, Esc closes (asks first if there are unsaved changes), Ctrl+F filters.
- Open it with Ctrl+Shift+, and from the sidebar menu ("Claude settings").
- Done when: you can open a skill, edit it, save it, and see the change on disk.

### Stage 3: validation and precedence — done 2026-10-12
- Add Jackson. A `.json` file must parse before it is saved; unknown keys are kept.
- Agents: `name` and `description` are required, and `name` has no `:`.
- A shadowed item (for example a project skill hidden by a user skill) shows a "shadowed by …" label.
- Done when: saving broken JSON is blocked with the parse error, and tests cover the precedence rules.

### Stage 4: create new — done 2026-10-12
- "New skill / agent / command / rule" opens a template with front-matter in the chosen scope.
- Done when: a new skill made in the window appears in Claude's `/` menu without a restart.

### Later (not planned yet)
- A structured editor for hooks and MCP servers (a table instead of raw JSON).
- A read-only plugins list.
