# Claude Terminal FX

Tabbed JavaFX terminal for Claude Code. Java 21, JavaFX 21, [JediTermFX](https://github.com/techsenger/jeditermfx) 1.1.0
(JavaFX Canvas port of JetBrains JediTerm), [pty4j](https://github.com/JetBrains/pty4j) 0.14.0.

```bash
./run.sh ~/some/project      # builds on first run, opens a Claude tab in that directory
```

| Key | Action |
|---|---|
| Ctrl+Shift+N | new Claude tab (choose directory) |
| Ctrl+Shift+T | new shell tab |
| Ctrl+Shift+W | close tab |
| Ctrl+Tab / Ctrl+Shift+Tab | next / previous tab |
| Shift+Enter | new line in the Claude prompt (sends ESC CR) |

When `claude` exits, the tab stays open as a login shell.

## What it adds on top of JediTermFX

- `SynchronizedOutputReader`: DEC mode 2026. Claude Code frames are drawn in one step, so less flicker.
- `TERM=xterm-256color`, `COLORTERM=truecolor`; `CLAUDECODE` is removed, so a nested start works.

Not supported (Claude falls back): kitty keyboard protocol, theme-change notify (mode 2031).

## Build

Gradle 9.8.1 wrapper, Java 21 toolchain.

- Dev run: `./gradlew run --args="/path/to/project"`
- IntelliJ: File → Open → `build.gradle.kts` → Open as Project
