# Next five features

Order runs one stage at a time: each stage edits `FabricManagerApp.java`, so they cannot run in parallel.
Free Ctrl+Shift keys before this plan: B, C, F, I, L, M, U, X, Y, Z.

## Stage 1 — Worktree per session (Ctrl+Shift+B)
Status: done
Action "Worktree": take the git root of the selected tab's folder, ask for a branch name, run
`git worktree add <root-parent>/<root-name>-worktrees/<branch>` (`-b` when the branch is new), open a
claude tab there. `GitRepo#addWorktree` plus tests on a temp repo.
Done when: `./gradlew test` is green; new GitRepo tests cover a new branch, an existing branch, and a bad name.

## Stage 2 — Tab status dot + status for non-Claude tabs
Status: done
A coloured `Circle` graphic on each tab header (colour from `SessionSidebar.runningColor`, red when
exited). Non-Claude tabs: `lastOutput` timestamp from the pty read path; busy = output in the last 2 s,
otherwise idle. Sidebar, agents panel and overview use the same state.
Done when: tests cover the activity→colour mapping and the 2 s busy rule (injected clock).

## Stage 3 — SSH tab closes when ssh exits

Status: done
SSH tabs run `ssh <alias>` with no `; exec $SHELL` suffix. On exit the tab shows
"Disconnected — Enter to reconnect, Esc to close".
Done when: a test shows the ssh launch command has no shell suffix; other tools keep it.

## Stage 4 — Changes panel (Ctrl+Shift+C)
Status: done
Window for the selected tab's git folder: changed files list, diff of the chosen file (reuse
`git/DiffView`), buttons Stage all + Commit (message field) and Discard file (confirm first).
Done when: GitRepo tests cover status, diff, commit and discard on a temp repo.

## Stage 5 — Transcript search (Ctrl+Shift+F)
Status: done
Search text across Claude transcripts (`~/.claude*/projects/*/*.jsonl` for every Account), list hits
with project, date and snippet, Enter resumes that session.
Done when: tests cover search over sample jsonl files (match, no match, case-insensitive, limit).
