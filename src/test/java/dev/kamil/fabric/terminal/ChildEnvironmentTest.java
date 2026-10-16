package dev.kamil.fabric.terminal;

import dev.kamil.fabric.session.Account;
import dev.kamil.fabric.session.SavedSession;
import dev.kamil.fabric.session.Tool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ChildEnvironmentTest {

    @Test
    void dropsClaudeSessionMarkers() {
        var env = TerminalTab.childEnvironment(Map.of(
                "CLAUDECODE", "1",
                "CLAUDE_PID", "42",
                "CLAUDE_CODE_ENTRYPOINT", "cli",
                "CLAUDE_CODE_CHILD_SESSION", "1",
                "CLAUDE_CODE_SESSION_ID", "abc",
                "HOME", "/home/me"));
        assertFalse(env.keySet().stream().anyMatch(k -> k.startsWith("CLAUDE")), env.toString());
        assertEquals("/home/me", env.get("HOME"));
    }

    @Test
    void setsTerminalType() {
        var env = TerminalTab.childEnvironment(Map.of("TERM", "dumb"));
        assertEquals("xterm-256color", env.get("TERM"));
        assertEquals("truecolor", env.get("COLORTERM"));
        assertEquals("fabric-manager", env.get("TERM_PROGRAM"));
    }

    @Test
    void dropsTheIdeTerminalIdentity() {
        var env = TerminalTab.childEnvironment(Map.of("TERMINAL_EMULATOR", "JetBrains-JediTerm", "HOME", "/home/me"));
        assertFalse(env.containsKey("TERMINAL_EMULATOR"), env.toString());
    }

    @Test
    void addsEventVariables() {
        var env = TerminalTab.childEnvironment(Map.of(), java.nio.file.Path.of("/run/ev"), "abc", PERSONAL);
        assertEquals("/run/ev", env.get("FABRIC_EVENTS"));
        assertEquals("abc", env.get("FABRIC_SESSION"));
    }

    private static final Tool CLAUDE = new Tool("claude", "claude");
    private static final Account PERSONAL = Account.personal(java.nio.file.Path.of("/h"));
    private static final Account WORK = new Account("work", java.nio.file.Path.of("/h/.claude-work"));

    private static SavedSession saved(Tool tool, String id) {
        return saved(tool, id, PERSONAL);
    }

    private static SavedSession saved(Tool tool, String id, Account account) {
        return new SavedSession(id, java.nio.file.Path.of("/p"), tool, "t", java.time.Instant.now(), null, null,
                account);
    }

    @Test
    void teamAccountSetsConfigDir() {
        var env = TerminalTab.childEnvironment(Map.of(), java.nio.file.Path.of("/run/ev"), "abc", WORK);
        assertEquals("/h/.claude-work", env.get("CLAUDE_CONFIG_DIR"));
    }

    @Test
    void personalAccountLeavesConfigDirUnset() {
        var env = TerminalTab.childEnvironment(Map.of("CLAUDE_CONFIG_DIR", "/elsewhere"),
                java.nio.file.Path.of("/run/ev"), "abc", PERSONAL);
        assertFalse(env.containsKey("CLAUDE_CONFIG_DIR"));
    }

    @Test
    void resumeFindsTranscriptUnderTeamConfigDir(@org.junit.jupiter.api.io.TempDir java.nio.file.Path home)
            throws java.io.IOException {
        var work = new Account("work", home.resolve(".claude-work"));
        var dir = java.nio.file.Files.createDirectories(work.configDir().resolve("projects/-p"));
        java.nio.file.Files.writeString(dir.resolve("abc.jsonl"), "{}");
        var launch = TerminalTab.resumeLaunch(saved(CLAUDE, "abc", work), java.nio.file.Path.of("/plug"));
        assertEquals("abc", launch.sessionId());
    }

    @Test
    void claudeLaunchLoadsPluginWithQuotedPathAndSessionId() {
        var cmd = TerminalTab.launchCommand(CLAUDE, java.nio.file.Path.of("/a b/it's"), "abc");
        assertEquals("claude --plugin-dir '/a b/it'\\''s' --session-id 'abc'", cmd);
    }

    @Test
    void otherToolsLaunchUnchanged() {
        assertEquals("codex",
                TerminalTab.launchCommand(new Tool("codex", "codex"), java.nio.file.Path.of("/x"), "abc"));
    }

    @Test
    void claudeResumeUsesResumeWhenTranscriptExists(@org.junit.jupiter.api.io.TempDir java.nio.file.Path home)
            throws java.io.IOException {
        var dir = java.nio.file.Files.createDirectories(home.resolve("projects/-p"));
        java.nio.file.Files.writeString(dir.resolve("abc.jsonl"), "{}");
        var launch = TerminalTab.resumeLaunch(saved(CLAUDE, "abc", new Account("personal", home)),
                java.nio.file.Path.of("/plug"));
        assertEquals("abc", launch.sessionId());
        assertEquals("claude --plugin-dir '/plug' --resume 'abc'", launch.command());
    }

    @Test
    void claudeResumeFallsBackToFreshSessionWithoutTranscript(@org.junit.jupiter.api.io.TempDir java.nio.file.Path home) {
        var launch = TerminalTab.resumeLaunch(saved(CLAUDE, "abc", new Account("personal", home)),
                java.nio.file.Path.of("/plug"));
        assertNotEquals("abc", launch.sessionId());
        assertEquals("claude --plugin-dir '/plug' --session-id '" + launch.sessionId() + "'", launch.command());
    }

    @Test
    void otherToolResumeIsFreshWithoutClaudeFlags(@org.junit.jupiter.api.io.TempDir java.nio.file.Path home) {
        var launch = TerminalTab.resumeLaunch(saved(new Tool("codex", "codex"), "abc"),
                java.nio.file.Path.of("/plug"));
        assertNotEquals("abc", launch.sessionId());
        assertEquals("codex", launch.command());
    }
}
