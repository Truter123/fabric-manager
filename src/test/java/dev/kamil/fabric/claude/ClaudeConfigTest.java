package dev.kamil.fabric.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.claude.ClaudeConfig.Item;
import dev.kamil.fabric.claude.ClaudeConfig.Kind;
import dev.kamil.fabric.claude.ClaudeConfig.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudeConfigTest {

    @TempDir
    Path tmp;
    Path project;
    Path home;
    Path managed;

    @BeforeEach
    void dirs() throws IOException {
        project = Files.createDirectories(tmp.resolve("project"));
        home = Files.createDirectories(tmp.resolve("home"));
        managed = tmp.resolve("managed");
    }

    private Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    private Map<Scope, Map<Kind, List<Item>>> scan() {
        return ClaudeConfig.scan(project, home, managed);
    }

    private List<Item> items(Scope scope, Kind kind) {
        return scan().getOrDefault(scope, Map.of()).getOrDefault(kind, List.of());
    }

    private List<String> names(Scope scope, Kind kind) {
        return items(scope, kind).stream().map(Item::name).toList();
    }

    @Test
    void missingEverythingGivesEmptyTree() {
        assertTrue(scan().isEmpty());
    }

    @Test
    void settingsInThreeScopes() throws IOException {
        write(home.resolve(".claude/settings.json"), "{}");
        write(project.resolve(".claude/settings.json"), "{}");
        write(project.resolve(".claude/settings.local.json"), "{}");
        assertEquals(List.of("settings.json"), names(Scope.USER, Kind.SETTINGS));
        assertEquals(List.of("settings.json"), names(Scope.PROJECT, Kind.SETTINGS));
        assertEquals(List.of("settings.local.json"), names(Scope.LOCAL, Kind.SETTINGS));
        assertEquals("", items(Scope.USER, Kind.SETTINGS).get(0).description());
    }

    @Test
    void memoryUser() throws IOException {
        write(home.resolve(".claude/CLAUDE.md"), "x");
        write(home.resolve(".claude/rules/a.md"), "x");
        write(home.resolve(".claude/rules/sub/b.md"), "x");
        assertEquals(List.of("CLAUDE", "a", "b"), names(Scope.USER, Kind.MEMORY));
    }

    @Test
    void memoryProjectRootAndDotClaude() throws IOException {
        write(project.resolve("CLAUDE.md"), "x");
        write(project.resolve(".claude/CLAUDE.md"), "x");
        write(project.resolve(".claude/rules/deep/er/r.md"), "x");
        assertEquals(3, items(Scope.PROJECT, Kind.MEMORY).size());
        assertTrue(names(Scope.PROJECT, Kind.MEMORY).contains("r"));
    }

    @Test
    void memoryLocal() throws IOException {
        write(project.resolve("CLAUDE.local.md"), "x");
        assertEquals(List.of("CLAUDE.local"), names(Scope.LOCAL, Kind.MEMORY));
    }

    @Test
    void skillsUserAndProject() throws IOException {
        write(home.resolve(".claude/skills/zeta/SKILL.md"), "no front matter");
        write(home.resolve(".claude/skills/alpha/SKILL.md"), "---\nname: renamed\n---\n");
        write(home.resolve(".claude/skills/empty/other.txt"), "x");
        write(project.resolve(".claude/skills/p/SKILL.md"), "x");
        assertEquals(List.of("renamed", "zeta"), names(Scope.USER, Kind.SKILLS));
        assertEquals(List.of("p"), names(Scope.PROJECT, Kind.SKILLS));
        assertTrue(items(Scope.LOCAL, Kind.SKILLS).isEmpty());
    }

    @Test
    void agentsUserFlatProjectRecursive() throws IOException {
        write(home.resolve(".claude/agents/u.md"), "x");
        write(home.resolve(".claude/agents/nested/ignored.md"), "x");
        write(project.resolve(".claude/agents/p.md"), "x");
        write(project.resolve(".claude/agents/team/q.md"), "x");
        assertEquals(List.of("u"), names(Scope.USER, Kind.AGENTS));
        assertEquals(List.of("p", "q"), names(Scope.PROJECT, Kind.AGENTS));
    }

    @Test
    void commandsRecursive() throws IOException {
        write(home.resolve(".claude/commands/a.md"), "x");
        write(home.resolve(".claude/commands/ns/b.md"), "x");
        write(project.resolve(".claude/commands/c.md"), "x");
        assertEquals(List.of("a", "b"), names(Scope.USER, Kind.COMMANDS));
        assertEquals(List.of("c"), names(Scope.PROJECT, Kind.COMMANDS));
    }

    @Test
    void outputStyles() throws IOException {
        write(home.resolve(".claude/output-styles/s.md"), "x");
        write(project.resolve(".claude/output-styles/t.md"), "x");
        assertEquals(List.of("s"), names(Scope.USER, Kind.OUTPUT_STYLES));
        assertEquals(List.of("t"), names(Scope.PROJECT, Kind.OUTPUT_STYLES));
    }

    @Test
    void mcpFilesAndReadOnly() throws IOException {
        write(home.resolve(".claude.json"), "{}");
        write(project.resolve(".mcp.json"), "{}");
        var user = items(Scope.USER, Kind.MCP);
        var local = items(Scope.LOCAL, Kind.MCP);
        var shared = items(Scope.PROJECT, Kind.MCP);
        assertEquals(home.resolve(".claude.json"), user.get(0).path());
        assertTrue(user.get(0).readOnly());
        assertTrue(local.get(0).readOnly());
        assertEquals(".mcp.json", shared.get(0).name());
        assertFalse(shared.get(0).readOnly());
    }

    @Test
    void keybindingsUserOnly() throws IOException {
        write(home.resolve(".claude/keybindings.json"), "{}");
        assertEquals(List.of("keybindings.json"), names(Scope.USER, Kind.KEYBINDINGS));
        assertFalse(scan().containsKey(Scope.PROJECT));
    }

    @Test
    void managedItemsAreReadOnly() throws IOException {
        write(managed.resolve("managed-settings.json"), "{}");
        write(managed.resolve("CLAUDE.md"), "x");
        assertTrue(items(Scope.MANAGED, Kind.SETTINGS).get(0).readOnly());
        assertTrue(items(Scope.MANAGED, Kind.MEMORY).get(0).readOnly());
    }

    @Test
    void editableItemsAreNotReadOnlyAndPluginsAreIgnored() throws IOException {
        write(home.resolve(".claude/settings.json"), "{}");
        write(home.resolve(".claude/plugins/p/skills/s/SKILL.md"), "x");
        write(home.resolve(".claude/plugins/agents/a.md"), "x");
        assertFalse(items(Scope.USER, Kind.SETTINGS).get(0).readOnly());
        assertEquals(1, scan().get(Scope.USER).size());
    }

    @Test
    void itemsSortedByName() throws IOException {
        write(project.resolve(".claude/commands/b.md"), "x");
        write(project.resolve(".claude/commands/a.md"), "x");
        write(project.resolve(".claude/commands/c.md"), "x");
        assertEquals(List.of("a", "b", "c"), names(Scope.PROJECT, Kind.COMMANDS));
    }

    @Test
    void frontMatterNameAndDescription() throws IOException {
        write(project.resolve(".claude/agents/x.md"), "---\nname: my-agent\ndescription: Does things\ntools: Read\n---\nbody");
        var item = items(Scope.PROJECT, Kind.AGENTS).get(0);
        assertEquals("my-agent", item.name());
        assertEquals("Does things", item.description());
    }

    @Test
    void frontMatterQuotedValues() throws IOException {
        write(project.resolve(".claude/agents/x.md"), "---\nname: \"quoted\"\ndescription: 'single: quoted'\n---\n");
        var item = items(Scope.PROJECT, Kind.AGENTS).get(0);
        assertEquals("quoted", item.name());
        assertEquals("single: quoted", item.description());
    }

    @Test
    void frontMatterFoldedDescription() throws IOException {
        write(project.resolve(".claude/agents/x.md"), "---\ndescription: >\n  first line\n  second line\nname: n\n---\n");
        var item = items(Scope.PROJECT, Kind.AGENTS).get(0);
        assertEquals("first line second line", item.description());
        assertEquals("n", item.name());
    }

    @Test
    void frontMatterLiteralDescription() throws IOException {
        write(project.resolve(".claude/agents/x.md"), "---\ndescription: |\n  a\n  b\n---\n");
        assertEquals("a b", items(Scope.PROJECT, Kind.AGENTS).get(0).description());
    }

    @Test
    void noFrontMatterUsesFileName() throws IOException {
        write(project.resolve(".claude/agents/plain.md"), "just text\nname: nope\n");
        var item = items(Scope.PROJECT, Kind.AGENTS).get(0);
        assertEquals("plain", item.name());
        assertEquals("", item.description());
    }

    @Test
    void frontMatterNotOnFirstLineIsIgnored() throws IOException {
        write(project.resolve(".claude/agents/late.md"), "\n---\nname: nope\ndescription: nope\n---\n");
        var item = items(Scope.PROJECT, Kind.AGENTS).get(0);
        assertEquals("late", item.name());
        assertEquals("", item.description());
    }

    @Test
    void userScopeReadsFromAccountDirAndMcpFile() throws IOException {
        var work = new dev.kamil.fabric.session.Account("work", home.resolve(".claude-work"));
        write(home.resolve(".claude/settings.json"), "{}");
        write(work.configDir().resolve("settings.json"), "{}");
        write(work.configDir().resolve(".claude.json"), "{\"mcpServers\":{\"w\":{}}}");
        var result = ClaudeConfig.scan(project, home, work, managed);
        var settings = result.get(Scope.USER).get(Kind.SETTINGS);
        assertEquals(1, settings.size());
        assertEquals(work.configDir().resolve("settings.json"), settings.get(0).path());
        assertEquals(work.configDir().resolve(".claude.json"), result.get(Scope.USER).get(Kind.MCP).get(0).path());
    }
}
