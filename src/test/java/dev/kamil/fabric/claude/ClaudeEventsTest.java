package dev.kamil.fabric.claude;

import dev.kamil.fabric.terminal.TerminalTab;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudeEventsTest {

    @Test
    void hooksJsonHasEveryEventWithItsState() {
        var json = ClaudeEvents.hooksJson();
        for (var event : new String[]{"UserPromptSubmit", "PostToolUse", "Stop", "StopFailure",
                "PermissionRequest", "PreToolUse", "Notification"}) {
            assertTrue(json.contains("\"" + event + "\":["), event);
        }
        for (var state : new String[]{"working", "done", "error", "waiting"}) {
            assertTrue(json.contains("printf '" + state + "\\\\n'"), state);
        }
        assertTrue(json.contains("\"matcher\":\"AskUserQuestion|ExitPlanMode\""));
        assertTrue(json.contains("\"matcher\":\"elicitation_dialog\""));
        assertTrue(json.contains("[ -n \\\"$FABRIC_EVENTS\\\" ]"));
    }

    @Test
    void hooksJsonIsBalanced() {
        var json = ClaudeEvents.hooksJson();
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                assertTrue(depth >= 0);
            }
        }
        assertEquals(0, depth);
        assertTrue(!inString);
    }

    @Test
    void installWritesBothFiles(@TempDir Path dir) throws Exception {
        ClaudeEvents.installPlugin(dir);
        assertTrue(Files.readString(dir.resolve(".claude-plugin/plugin.json")).contains("fabric-notify"));
        assertEquals(ClaudeEvents.hooksJson(), Files.readString(dir.resolve("hooks/hooks.json")));
    }

    @Test
    void parsesStates() {
        assertEquals(TerminalTab.Activity.WORKING, ClaudeEvents.parse("working"));
        assertEquals(TerminalTab.Activity.WAITING, ClaudeEvents.parse("waiting"));
        assertEquals(TerminalTab.Activity.DONE, ClaudeEvents.parse("done\n"));
        assertEquals(TerminalTab.Activity.ERROR, ClaudeEvents.parse("error"));
        assertNull(ClaudeEvents.parse("garbage"));
        assertNull(ClaudeEvents.parse(""));
        assertNull(ClaudeEvents.parse(null));
    }

    @Test
    void lastLineSkipsBlankLines() {
        assertEquals("done", ClaudeEvents.lastLine("working\ndone\n\n"));
        assertNull(ClaudeEvents.lastLine("\n"));
    }

    @Test
    void eventsDirFollowsRuntimeDir() {
        var home = Path.of("/home/me");
        assertEquals(Path.of("/run/user/1/fabric-manager/events"), ClaudeEvents.eventsDir("/run/user/1", home));
        assertEquals(home.resolve(".cache/fabric-manager/events"), ClaudeEvents.eventsDir(null, home));
    }

    @Test
    void clearStaleRemovesOldFilesButKeepsLiveAndRecentOnes(@TempDir Path dir) throws Exception {
        var dayAgo = java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofHours(25)));
        Files.writeString(dir.resolve("old"), "done\n");
        Files.setLastModifiedTime(dir.resolve("old"), dayAgo);
        Files.writeString(dir.resolve("live"), "working\n");
        Files.setLastModifiedTime(dir.resolve("live"), dayAgo);
        Files.writeString(dir.resolve("other-instance"), "working\n");
        Files.createDirectory(dir.resolve("sub"));
        ClaudeEvents.clearStale(dir, java.util.Set.of("live"));
        assertTrue(Files.notExists(dir.resolve("old")));
        assertTrue(Files.exists(dir.resolve("live")));
        assertTrue(Files.exists(dir.resolve("other-instance")));
        assertTrue(Files.isDirectory(dir.resolve("sub")));
    }

    @Test
    void hooksJsonHasOnlyActivityHooks() throws Exception {
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(ClaudeEvents.hooksJson());
        var hooks = root.get("hooks");
        assertEquals(1, hooks.get("PreToolUse").size());
        assertTrue(!hooks.has("SubagentStart"));
        assertTrue(!hooks.has("SubagentStop"));
        assertTrue(!hooks.get("Stop").get(0).get("hooks").get(0).has("async"));
    }

    @Test
    void parsesActivityLines() {
        assertEquals(new ClaudeEvents.ActivityChanged(TerminalTab.Activity.DONE), ClaudeEvents.parseLine("done"));
    }

    @Test
    void parseLineRejectsGarbage() {
        assertNull(ClaudeEvents.parseLine(null));
        assertNull(ClaudeEvents.parseLine(""));
        assertNull(ClaudeEvents.parseLine("garbage"));
    }

    @Test
    void readNewKeepsPartialLastLineAndHandlesTruncation(@TempDir Path dir) throws Exception {
        var file = dir.resolve("s");
        Files.writeString(file, "working\ndo");
        var first = ClaudeEvents.readNew(file, 0);
        assertEquals(java.util.List.of("working"), first.lines());
        assertEquals(8, first.offset());
        Files.writeString(file, "working\ndone\nerror\n");
        var second = ClaudeEvents.readNew(file, first.offset());
        assertEquals(java.util.List.of("done", "error"), second.lines());
        Files.writeString(file, "waiting\n");
        var third = ClaudeEvents.readNew(file, second.offset());
        assertEquals(java.util.List.of("waiting"), third.lines());
        assertEquals(java.util.List.of(), ClaudeEvents.readNew(file, third.offset()).lines());
    }
}
