package dev.kamil.fabric.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.claude.ClaudeConfig.Item;
import dev.kamil.fabric.claude.ClaudeConfig.Kind;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClaudeValidationTest {

    private static List<String> json(String text) {
        return ClaudeValidation.problems(new Item(Path.of("/x/settings.json"), "settings.json", "", false), Kind.SETTINGS, text);
    }

    private static List<String> agent(String text) {
        return ClaudeValidation.problems(new Item(Path.of("/x/agents/a.md"), "a", "", false), Kind.AGENTS, text);
    }

    private static String agentWithName(String name) {
        return "---\nname: " + name + "\ndescription: d\n---\nbody\n";
    }

    @Test
    void validJson() {
        assertTrue(json("{\"a\": 1}").isEmpty());
    }

    @Test
    void brokenJsonReportsLocation() {
        var problems = json("{\n  \"a\": 1,\n  \"b\": \n");
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("line 4") || problems.get(0).contains("line 3"), problems.get(0));
        assertTrue(problems.get(0).contains("column"), problems.get(0));
    }

    @Test
    void unknownKeysAccepted() {
        assertTrue(json("{\"someFutureKey\": {\"x\": [1, 2]}}").isEmpty());
    }

    @Test
    void blankJsonIsAProblem() {
        assertEquals(List.of("empty JSON file"), json("  \n"));
    }

    @Test
    void agentMissingName() {
        assertEquals(List.of("Agent needs a name"), agent("---\ndescription: d\n---\n"));
    }

    @Test
    void agentMissingDescription() {
        assertEquals(List.of("Agent needs a description"), agent("---\nname: a\n---\n"));
    }

    @Test
    void agentNameWithColon() {
        assertEquals(1, agent(agentWithName("\"a:b\"")).size());
    }

    @Test
    void agentNameLeadingDash() {
        assertEquals(1, agent(agentWithName("-a")).size());
    }

    @Test
    void agentNameTooLong() {
        assertEquals(1, agent(agentWithName("a".repeat(257))).size());
        assertTrue(agent(agentWithName("a".repeat(256))).isEmpty());
    }

    @Test
    void frontMatterMustStartOnLineOne() {
        assertEquals(1, agent("\n---\nname: a\ndescription: d\n---\n").size());
    }

    @Test
    void validAgent() {
        assertTrue(agent(agentWithName("reviewer")).isEmpty());
    }

    @Test
    void nonAgentMarkdownHasNoProblems() {
        var item = new Item(Path.of("/x/CLAUDE.md"), "CLAUDE", "", false);
        assertTrue(ClaudeValidation.problems(item, Kind.MEMORY, "no front matter").isEmpty());
    }
}
