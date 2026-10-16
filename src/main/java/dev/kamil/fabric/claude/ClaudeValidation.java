package dev.kamil.fabric.claude;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/** Pure checks run before a Claude configuration file is saved; the text itself is never rewritten. */
final class ClaudeValidation {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_NAME = 256;

    private ClaudeValidation() {
    }

    /** Human-readable problems that block saving {@code text} as {@code item}; empty when it is fine. */
    static List<String> problems(ClaudeConfig.Item item, ClaudeConfig.Kind kind, String text) {
        var problems = new ArrayList<String>();
        var content = text == null ? "" : text;
        var fileName = item.path().getFileName().toString();
        if (fileName.endsWith(".json")) {
            jsonProblem(content).ifPresent(problems::add);
        }
        if (kind == ClaudeConfig.Kind.AGENTS && fileName.endsWith(".md")) {
            agentProblems(content, problems);
        }
        return problems;
    }

    private static java.util.Optional<String> jsonProblem(String text) {
        if (text.isBlank()) {
            return java.util.Optional.of("empty JSON file");
        }
        try {
            JSON.readTree(text);
            return java.util.Optional.empty();
        } catch (JsonProcessingException e) {
            var at = e.getLocation();
            var where = at == null ? "" : " (line " + at.getLineNr() + ", column " + at.getColumnNr() + ")";
            return java.util.Optional.of("Invalid JSON: " + e.getOriginalMessage() + where);
        }
    }

    private static void agentProblems(String text, List<String> problems) {
        var lines = text.lines().toList();
        if (lines.isEmpty() || !lines.get(0).strip().equals("---")) {
            problems.add("Agent front-matter (---) must start on line 1");
            return;
        }
        var fm = ClaudeConfig.frontMatter(lines);
        var name = fm.getOrDefault("name", "");
        if (name.isEmpty()) {
            problems.add("Agent needs a name");
        } else {
            if (name.contains(":")) {
                problems.add("Agent name must not contain ':'");
            }
            if (name.startsWith("-")) {
                problems.add("Agent name must not start with '-'");
            }
            if (name.length() > MAX_NAME) {
                problems.add("Agent name is longer than " + MAX_NAME + " characters");
            }
        }
        if (fm.getOrDefault("description", "").isEmpty()) {
            problems.add("Agent needs a description");
        }
    }
}
