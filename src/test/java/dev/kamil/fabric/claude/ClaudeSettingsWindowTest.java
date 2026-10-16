package dev.kamil.fabric.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClaudeSettingsWindowTest {

    private static ClaudeConfig.Item item(String name, String description, boolean readOnly) {
        return new ClaudeConfig.Item(Path.of("/x/" + name), name, description, readOnly);
    }

    private static final Map<ClaudeConfig.Scope, Map<ClaudeConfig.Kind, List<ClaudeConfig.Item>>> SCAN = Map.of(
            ClaudeConfig.Scope.USER, Map.of(
                    ClaudeConfig.Kind.SKILLS, List.of(item("deploy", "Ship it", false), item("lint", "", false)),
                    ClaudeConfig.Kind.SETTINGS, List.of(item("settings.json", "", false))),
            ClaudeConfig.Scope.MANAGED, Map.of(
                    ClaudeConfig.Kind.SETTINGS, List.of(item("managed-settings.json", "", true))));

    @Test
    void treeKeepsScopeAndKindOrder() {
        var tree = ClaudeSettingsWindow.buildTree(SCAN, "");
        assertEquals(List.of("User (~/.claude)", "Managed"), tree.stream().map(ClaudeSettingsWindow.Node::label).toList());
        var kinds = tree.get(0).children();
        assertEquals(List.of("Settings", "Skills"), kinds.stream().map(ClaudeSettingsWindow.Node::label).toList());
        assertEquals(2, kinds.get(1).children().size());
        assertTrue(kinds.get(1).children().get(0).isFile());
    }

    @Test
    void filterMatchesNameOrDescriptionIgnoringCaseAndHidesEmptyGroups() {
        var tree = ClaudeSettingsWindow.buildTree(SCAN, "SHIP");
        assertEquals(1, tree.size());
        var kinds = tree.get(0).children();
        assertEquals(1, kinds.size());
        assertEquals("deploy", kinds.get(0).children().get(0).label());
        assertTrue(ClaudeSettingsWindow.buildTree(SCAN, "nothing").isEmpty());
        assertEquals(1, ClaudeSettingsWindow.buildTree(SCAN, "managed").size());
    }

    @Test
    void dirtyCheck() {
        assertFalse(ClaudeSettingsWindow.isDirty("a", "a"));
        assertTrue(ClaudeSettingsWindow.isDirty("a", "b"));
        assertFalse(ClaudeSettingsWindow.isDirty(null, ""));
    }

    @Test
    void readOnlyReasons() {
        assertNull(ClaudeSettingsWindow.readOnlyReason(item("a.md", "", false)));
        assertEquals("Claude's own state — use `claude mcp`",
                ClaudeSettingsWindow.readOnlyReason(new ClaudeConfig.Item(Path.of("/h/.claude.json"), ".claude.json", "", true)));
        assertEquals("managed by your admin", ClaudeSettingsWindow.readOnlyReason(item("m.json", "", true)));
    }
}
