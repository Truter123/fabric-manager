package dev.kamil.fabric.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.claude.ClaudeConfig.Item;
import dev.kamil.fabric.claude.ClaudeConfig.Kind;
import dev.kamil.fabric.claude.ClaudeConfig.Scope;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClaudeShadowingTest {

    private static Item item(String path, String name) {
        return new Item(Path.of(path), name, "", false);
    }

    private static Map<Scope, Map<Kind, List<Item>>> scan(Scope scope, Kind kind, Item... items) {
        return Map.of(scope, Map.of(kind, List.of(items)));
    }

    @Test
    void userSkillShadowsProjectSkill() {
        var user = item("/u/s/SKILL.md", "deploy");
        var project = item("/p/s/SKILL.md", "deploy");
        var scan = Map.of(Scope.USER, Map.of(Kind.SKILLS, List.of(user)), Scope.PROJECT, Map.of(Kind.SKILLS, List.of(project)));
        assertEquals(Map.of(project.path(), "shadowed by user skill"), ClaudeConfig.shadowing(scan));
    }

    @Test
    void skillShadowsCommand() {
        var skill = item("/p/s/SKILL.md", "deploy");
        var command = item("/u/c/deploy.md", "deploy");
        var scan = Map.of(Scope.PROJECT, Map.of(Kind.SKILLS, List.of(skill)), Scope.USER, Map.of(Kind.COMMANDS, List.of(command)));
        assertEquals(Map.of(command.path(), "shadowed by project skill"), ClaudeConfig.shadowing(scan));
    }

    @Test
    void projectAgentShadowsUserAgent() {
        var user = item("/u/agents/a.md", "a");
        var project = item("/p/agents/a.md", "a");
        var scan = Map.of(Scope.USER, Map.of(Kind.AGENTS, List.of(user)), Scope.PROJECT, Map.of(Kind.AGENTS, List.of(project)));
        assertEquals(Map.of(user.path(), "shadowed by project agent"), ClaudeConfig.shadowing(scan));
    }

    @Test
    void differentNamesNothingShadowed() {
        var scan = Map.of(Scope.USER, Map.of(Kind.SKILLS, List.of(item("/u/a", "a"))),
                Scope.PROJECT, Map.of(Kind.SKILLS, List.of(item("/p/b", "b"))));
        assertTrue(ClaudeConfig.shadowing(scan).isEmpty());
    }

    @Test
    void settingsAndMemoryNeverShadowed() {
        var scan = Map.of(
                Scope.USER, Map.of(Kind.SETTINGS, List.of(item("/u/settings.json", "settings.json")),
                        Kind.MEMORY, List.of(item("/u/CLAUDE.md", "CLAUDE"))),
                Scope.PROJECT, Map.of(Kind.SETTINGS, List.of(item("/p/settings.json", "settings.json")),
                        Kind.MEMORY, List.of(item("/p/CLAUDE.md", "CLAUDE"))));
        assertTrue(ClaudeConfig.shadowing(scan).isEmpty());
    }
}
