package dev.kamil.fabric.claude;

import java.nio.file.Path;
import java.util.regex.Pattern;

/** Pure parts of "create new": name rule, target path and starter text for a new skill, agent, command or rule. */
final class ClaudeTemplates {

    enum NewKind { SKILL, AGENT, COMMAND, RULE }

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    private ClaudeTemplates() {
    }

    /** True for 1-64 lowercase letters, digits and hyphens, not starting with a hyphen. */
    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /** The file a new item is written to; {@code base} is {@code ~/.claude} or {@code <project>/.claude}. */
    static Path target(Path base, NewKind kind, String name) {
        return switch (kind) {
            case SKILL -> base.resolve("skills").resolve(name).resolve("SKILL.md");
            case AGENT -> base.resolve("agents").resolve(name + ".md");
            case COMMAND -> base.resolve("commands").resolve(name + ".md");
            case RULE -> base.resolve("rules").resolve(name + ".md");
        };
    }

    /** The scan kind the new file is listed under. */
    static ClaudeConfig.Kind scanKind(NewKind kind) {
        return switch (kind) {
            case SKILL -> ClaudeConfig.Kind.SKILLS;
            case AGENT -> ClaudeConfig.Kind.AGENTS;
            case COMMAND -> ClaudeConfig.Kind.COMMANDS;
            case RULE -> ClaudeConfig.Kind.MEMORY;
        };
    }

    static String label(NewKind kind) {
        return switch (kind) {
            case SKILL -> "Skill";
            case AGENT -> "Agent";
            case COMMAND -> "Command";
            case RULE -> "Rule";
        };
    }

    /** Starter text with front-matter (none for a rule). */
    static String template(NewKind kind, String name) {
        return switch (kind) {
            case SKILL -> "---\nname: " + name + "\n"
                    + "description: One line: what this skill does and when to use it\n---\n\n"
                    + "# " + name + "\n\n## Steps\n\n1. First step\n2. Second step\n";
            case AGENT -> "---\nname: " + name + "\n"
                    + "description: One line: what this agent does and when to delegate to it\n"
                    + "tools: Read, Grep, Glob\nmodel: sonnet\n---\n\n"
                    + "You are a specialist. Describe the role, the steps to follow and the format of the answer.\n";
            case COMMAND -> "---\ndescription: One line: what this command does\nargument-hint: [args]\n---\n\n"
                    + "Describe the task here. Arguments: $ARGUMENTS\n";
            case RULE -> "# " + name + "\n\nDescribe the rule here.\n";
        };
    }
}
