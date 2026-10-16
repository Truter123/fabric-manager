package dev.kamil.fabric.claude;

import dev.kamil.fabric.Diagnostics;
import java.io.IOException;
import java.nio.file.Files;
import dev.kamil.fabric.session.Account;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Pure model of the Claude Code configuration files found for a project and a home directory. */
final class ClaudeConfig {

    /** Where a file lives: user home, shared project, local project, or the managed directory. */
    enum Scope { USER, PROJECT, LOCAL, MANAGED }

    /** What a file configures. */
    enum Kind { SETTINGS, MEMORY, SKILLS, AGENTS, COMMANDS, OUTPUT_STYLES, MCP, KEYBINDINGS }

    /** One file; description is empty when there is none. */
    record Item(Path path, String name, String description, boolean readOnly) {
    }

    /** Default directory of the managed (enterprise) configuration. */
    static final Path MANAGED_ROOT = Path.of("/etc/claude-code");

    private ClaudeConfig() {
    }

    /** Scans with the default managed root. */
    static Map<Scope, Map<Kind, List<Item>>> scan(Path project, Path home) {
        return scan(project, home, MANAGED_ROOT);
    }

    /** Scope to kind to items (sorted by name); empty kinds and scopes are omitted. */
    static Map<Scope, Map<Kind, List<Item>>> scan(Path project, Path home, Path managed) {
        return scan(project, home, Account.personal(home), managed);
    }

    /** Like {@link #scan(Path, Path, Path)}, with the user scope and MCP servers read from {@code account}. */
    static Map<Scope, Map<Kind, List<Item>>> scan(Path project, Path home, Account account, Path managed) {
        var result = new EnumMap<Scope, Map<Kind, List<Item>>>(Scope.class);

        var user = new Acc();
        var userDir = account.configDir();
        var mcpFile = account.mcpFile(home);
        user.file(Kind.SETTINGS, userDir.resolve("settings.json"), false);
        user.file(Kind.MEMORY, userDir.resolve("CLAUDE.md"), false);
        user.tree(Kind.MEMORY, userDir.resolve("rules"), false, false);
        dirs(user, userDir, false, false);
        user.file(Kind.MCP, mcpFile, true);
        user.file(Kind.KEYBINDINGS, userDir.resolve("keybindings.json"), false);
        put(result, Scope.USER, user);

        var shared = new Acc();
        var projDir = project.resolve(".claude");
        shared.file(Kind.SETTINGS, projDir.resolve("settings.json"), false);
        shared.file(Kind.MEMORY, project.resolve("CLAUDE.md"), false);
        shared.file(Kind.MEMORY, projDir.resolve("CLAUDE.md"), false);
        shared.tree(Kind.MEMORY, projDir.resolve("rules"), false, false);
        dirs(shared, projDir, false, true);
        shared.file(Kind.MCP, project.resolve(".mcp.json"), false);
        put(result, Scope.PROJECT, shared);

        var local = new Acc();
        local.file(Kind.SETTINGS, projDir.resolve("settings.local.json"), false);
        local.file(Kind.MEMORY, project.resolve("CLAUDE.local.md"), false);
        local.file(Kind.MCP, mcpFile, true);
        put(result, Scope.LOCAL, local);

        var man = new Acc();
        man.file(Kind.SETTINGS, managed.resolve("managed-settings.json"), true);
        man.file(Kind.MEMORY, managed.resolve("CLAUDE.md"), true);
        man.file(Kind.MCP, managed.resolve("managed-mcp.json"), true);
        put(result, Scope.MANAGED, man);

        return result;
    }

    /**
     * Items hidden by a same-named item of higher precedence, path to label. Skills: user beats project, and any skill
     * beats a command. Agents: project beats user. Settings, memory and hooks merge; MCP is not resolved here.
     */
    static Map<Path, String> shadowing(Map<Scope, Map<Kind, List<Item>>> scan) {
        var result = new java.util.HashMap<Path, String>();
        var userSkills = items(scan, Scope.USER, Kind.SKILLS);
        var projectSkills = items(scan, Scope.PROJECT, Kind.SKILLS);
        mark(result, projectSkills, userSkills, "shadowed by user skill");
        var skillNames = new java.util.HashMap<String, String>();
        for (var skill : projectSkills) {
            skillNames.put(skill.name(), "shadowed by project skill");
        }
        for (var skill : userSkills) {
            skillNames.put(skill.name(), "shadowed by user skill");
        }
        for (var scope : List.of(Scope.USER, Scope.PROJECT)) {
            for (var command : items(scan, scope, Kind.COMMANDS)) {
                var label = skillNames.get(command.name());
                if (label != null) {
                    result.put(command.path(), label);
                }
            }
        }
        mark(result, items(scan, Scope.USER, Kind.AGENTS), items(scan, Scope.PROJECT, Kind.AGENTS),
                "shadowed by project agent");
        return result;
    }

    private static List<Item> items(Map<Scope, Map<Kind, List<Item>>> scan, Scope scope, Kind kind) {
        return scan.getOrDefault(scope, Map.of()).getOrDefault(kind, List.of());
    }

    private static void mark(Map<Path, String> result, List<Item> losers, List<Item> winners, String label) {
        var names = new java.util.HashSet<String>();
        winners.forEach(w -> names.add(w.name()));
        for (var loser : losers) {
            if (names.contains(loser.name())) {
                result.put(loser.path(), label);
            }
        }
    }

    /** Skills, agents, commands and output styles under a .claude-like directory. */
    private static void dirs(Acc acc, Path dir, boolean readOnly, boolean agentsRecursive) {
        var skills = dir.resolve("skills");
        if (Files.isDirectory(skills)) {
            try (Stream<Path> s = Files.list(skills)) {
                s.sorted().forEach(d -> acc.add(Kind.SKILLS, d.resolve("SKILL.md"), d.getFileName().toString(), readOnly));
            } catch (IOException e) {
                Diagnostics.warnOnce("Cannot list " + skills + ": " + e.getMessage());
            }
        }
        acc.tree(Kind.AGENTS, dir.resolve("agents"), agentsRecursive, readOnly);
        acc.tree(Kind.COMMANDS, dir.resolve("commands"), true, readOnly);
        acc.tree(Kind.OUTPUT_STYLES, dir.resolve("output-styles"), false, readOnly);
    }

    private static void put(Map<Scope, Map<Kind, List<Item>>> result, Scope scope, Acc acc) {
        var kinds = new EnumMap<Kind, List<Item>>(Kind.class);
        acc.items.forEach((kind, list) -> {
            if (!list.isEmpty()) {
                list.sort(Comparator.comparing(Item::name).thenComparing(i -> i.path().toString()));
                kinds.put(kind, list);
            }
        });
        if (!kinds.isEmpty()) {
            result.put(scope, kinds);
        }
    }

    /** Collects items per kind. */
    private static final class Acc {
        final Map<Kind, List<Item>> items = new EnumMap<>(Kind.class);

        void file(Kind kind, Path file, boolean readOnly) {
            add(kind, file, null, readOnly);
        }

        void add(Kind kind, Path file, String fallbackName, boolean readOnly) {
            if (!Files.isRegularFile(file)) {
                return;
            }
            var fileName = file.getFileName().toString();
            var md = fileName.endsWith(".md");
            var fm = md ? frontMatter(file) : Map.<String, String>of();
            var name = fm.getOrDefault("name", "");
            if (name.isEmpty()) {
                name = fallbackName != null ? fallbackName : md ? fileName.substring(0, fileName.length() - 3) : fileName;
            }
            items.computeIfAbsent(kind, k -> new ArrayList<>()).add(new Item(file, name, fm.getOrDefault("description", ""), readOnly));
        }

        /** All .md files in a directory (recursively if asked). */
        void tree(Kind kind, Path dir, boolean recursive, boolean readOnly) {
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (Stream<Path> s = Files.walk(dir, recursive || kind == Kind.MEMORY ? Integer.MAX_VALUE : 1)) {
                s.filter(p -> p.getFileName().toString().endsWith(".md")).sorted()
                        .forEach(p -> add(kind, p, null, readOnly));
            } catch (IOException e) {
                Diagnostics.warnOnce("Cannot read " + dir + ": " + e.getMessage());
            }
        }
    }

    /** The {@code name} and {@code description} keys of a leading --- block; empty if the file has none. */
    static Map<String, String> frontMatter(Path file) {
        try {
            return frontMatter(Files.readAllLines(file));
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    /** Front-matter only counts when {@code ---} is on line 1; folded and literal blocks are joined with spaces. */
    static Map<String, String> frontMatter(List<String> lines) {
        var result = new java.util.HashMap<String, String>();
        if (lines.isEmpty() || !lines.get(0).strip().equals("---")) {
            return result;
        }
        for (var i = 1; i < lines.size(); i++) {
            var line = lines.get(i);
            if (line.strip().equals("---")) {
                break;
            }
            var key = line.startsWith("name:") ? "name" : line.startsWith("description:") ? "description" : null;
            if (key == null) {
                continue;
            }
            var value = line.substring(line.indexOf(':') + 1).strip();
            if (value.matches("[>|][-+]?")) {
                var parts = new ArrayList<String>();
                while (i + 1 < lines.size() && (lines.get(i + 1).startsWith(" ") || lines.get(i + 1).startsWith("\t")
                        || lines.get(i + 1).isBlank()) && !lines.get(i + 1).strip().equals("---")) {
                    var part = lines.get(++i).strip();
                    if (!part.isEmpty()) {
                        parts.add(part);
                    }
                }
                value = String.join(" ", parts);
            } else if (value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                    && value.charAt(value.length() - 1) == value.charAt(0)) {
                value = value.substring(1, value.length() - 1);
            }
            result.put(key, value);
        }
        return result;
    }
}
