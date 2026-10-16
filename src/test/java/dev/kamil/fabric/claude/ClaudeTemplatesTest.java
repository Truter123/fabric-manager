package dev.kamil.fabric.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.claude.ClaudeTemplates.NewKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudeTemplatesTest {

    @Test
    void nameRule() {
        assertTrue(ClaudeTemplates.validName("a"));
        assertTrue(ClaudeTemplates.validName("my-skill-2"));
        assertTrue(ClaudeTemplates.validName("9"));
        assertTrue(ClaudeTemplates.validName("a".repeat(64)));
        assertFalse(ClaudeTemplates.validName("a".repeat(65)));
        assertFalse(ClaudeTemplates.validName(""));
        assertFalse(ClaudeTemplates.validName(null));
        assertFalse(ClaudeTemplates.validName("-x"));
        assertFalse(ClaudeTemplates.validName("Upper"));
        assertFalse(ClaudeTemplates.validName("a_b"));
        assertFalse(ClaudeTemplates.validName("a b"));
        assertFalse(ClaudeTemplates.validName("a/b"));
    }

    @Test
    void targets() {
        var base = Path.of("/h/.claude");
        assertEquals(Path.of("/h/.claude/skills/x/SKILL.md"), ClaudeTemplates.target(base, NewKind.SKILL, "x"));
        assertEquals(Path.of("/h/.claude/agents/x.md"), ClaudeTemplates.target(base, NewKind.AGENT, "x"));
        assertEquals(Path.of("/h/.claude/commands/x.md"), ClaudeTemplates.target(base, NewKind.COMMAND, "x"));
        assertEquals(Path.of("/h/.claude/rules/x.md"), ClaudeTemplates.target(base, NewKind.RULE, "x"));
        assertEquals(Path.of("/p/.claude/skills/x/SKILL.md"),
                ClaudeTemplates.target(Path.of("/p/.claude"), NewKind.SKILL, "x"));
    }

    @Test
    void templatesPassValidationAndCarryName() {
        for (var kind : NewKind.values()) {
            var text = ClaudeTemplates.template(kind, "demo");
            var item = new ClaudeConfig.Item(ClaudeTemplates.target(Path.of("/x/.claude"), kind, "demo"), "demo", "", false);
            assertTrue(ClaudeValidation.problems(item, ClaudeTemplates.scanKind(kind), text).isEmpty(), kind.toString());
            if (kind == NewKind.RULE) {
                assertFalse(text.startsWith("---"));
            } else {
                assertTrue(text.startsWith("---\n"));
                var fm = ClaudeConfig.frontMatter(text.lines().toList());
                assertFalse(fm.getOrDefault("description", "").isEmpty());
                if (kind != NewKind.COMMAND) {
                    assertEquals("demo", fm.get("name"));
                }
            }
        }
        assertTrue(ClaudeTemplates.template(NewKind.COMMAND, "demo").contains("$ARGUMENTS"));
    }

    @Test
    void scanFindsNewSkill(@TempDir Path tmp) throws IOException {
        var home = tmp.resolve("home");
        var project = tmp.resolve("proj");
        Files.createDirectories(home);
        Files.createDirectories(project);
        var file = ClaudeTemplates.target(project.resolve(".claude"), NewKind.SKILL, "demo");
        Files.createDirectories(file.getParent());
        Files.writeString(file, ClaudeTemplates.template(NewKind.SKILL, "demo"));
        var skills = ClaudeConfig.scan(project, home, tmp.resolve("none")).get(ClaudeConfig.Scope.PROJECT)
                .get(ClaudeConfig.Kind.SKILLS);
        assertEquals(1, skills.size());
        assertEquals(file, skills.get(0).path());
        assertEquals("demo", skills.get(0).name());
    }
}
