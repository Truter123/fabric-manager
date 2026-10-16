package dev.kamil.fabric.ssh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads the concrete host aliases of an OpenSSH client config; no JavaFX. */
public final class SshConfig {

    private SshConfig() {
    }

    /** The user's ~/.ssh/config. */
    public static List<SshHost> standard() {
        return read(Path.of(System.getProperty("user.home"), ".ssh", "config"));
    }

    /**
     * Hosts in file order, wildcard and negated patterns skipped, aliases de-duplicated (first wins).
     * Relative Include paths resolve against the config file's directory (~/.ssh for the standard file).
     * A missing or unreadable file gives an empty list.
     */
    public static List<SshHost> read(Path configFile) {
        var blocks = new ArrayList<Block>();
        parse(configFile, configFile.toAbsolutePath().getParent(), new HashSet<>(), blocks);
        var hosts = new LinkedHashMap<String, SshHost>();
        for (var block : blocks) {
            for (var alias : block.aliases) {
                hosts.putIfAbsent(alias, new SshHost(alias, block.values.get("hostname"), block.values.get("user"),
                        block.port()));
            }
        }
        return List.copyOf(hosts.values());
    }

    private static final class Block {
        final List<String> aliases = new ArrayList<>();
        final Map<String, String> values = new LinkedHashMap<>();

        Integer port() {
            var raw = values.get("port");
            if (raw == null) {
                return null;
            }
            try {
                return Integer.valueOf(raw);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private static void parse(Path file, Path baseDir, Set<Path> visited, List<Block> blocks) {
        var real = file.toAbsolutePath().normalize();
        if (!visited.add(real) || !Files.isRegularFile(real)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(real);
        } catch (IOException | RuntimeException e) {
            return;
        }
        Block current = null;
        for (var raw : lines) {
            var line = withoutComment(raw).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int end = 0;
            while (end < line.length() && !Character.isWhitespace(line.charAt(end)) && line.charAt(end) != '=') {
                end++;
            }
            var keyword = line.substring(0, end).toLowerCase();
            var value = line.substring(end).strip();
            if (value.startsWith("=")) {
                value = value.substring(1).strip();
            }
            switch (keyword) {
                case "host" -> {
                    current = new Block();
                    for (var pattern : words(value)) {
                        var p = unquote(pattern);
                        if (!p.isEmpty() && !p.startsWith("!") && p.indexOf('*') < 0 && p.indexOf('?') < 0) {
                            current.aliases.add(p);
                        }
                    }
                    blocks.add(current);
                }
                case "match" -> current = null;
                case "include" -> {
                    for (var pattern : words(value)) {
                        for (var included : expand(unquote(pattern), baseDir)) {
                            parse(included, baseDir, visited, blocks);
                        }
                    }
                }
                case "hostname", "user", "port" -> {
                    if (current != null && !value.isEmpty()) {
                        current.values.putIfAbsent(keyword, unquote(value));
                    }
                }
                default -> {
                }
            }
        }
    }

    /** Splits on whitespace, keeping a double-quoted word together. */
    private static List<String> words(String value) {
        var words = new ArrayList<String>();
        var word = new StringBuilder();
        boolean quoted = false;
        for (char c : value.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (!word.isEmpty()) {
                    words.add(word.toString());
                    word.setLength(0);
                }
            } else {
                word.append(c);
            }
        }
        if (!word.isEmpty()) {
            words.add(word.toString());
        }
        return words;
    }

    /** Drops a trailing "# ..." comment (a # at the start of a word, outside double quotes). */
    private static String withoutComment(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == '#' && !quoted && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String text) {
        var t = text.strip();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    /** Files matching an Include pattern; the glob may be in the last path segment only. */
    private static List<Path> expand(String pattern, Path baseDir) {
        if (pattern.isEmpty()) {
            return List.of();
        }
        if (pattern.startsWith("~/")) {
            pattern = Path.of(System.getProperty("user.home")).resolve(pattern.substring(2)).toString();
        }
        var path = baseDir.resolve(pattern);
        var name = path.getFileName().toString();
        if (name.indexOf('*') < 0 && name.indexOf('?') < 0 && name.indexOf('[') < 0) {
            return List.of(path);
        }
        var dir = path.getParent();
        var found = new ArrayList<Path>();
        if (dir != null && Files.isDirectory(dir)) {
            try (var stream = Files.newDirectoryStream(dir, name)) {
                stream.forEach(found::add);
            } catch (IOException | RuntimeException e) {
                return List.of();
            }
        }
        found.sort(null);
        return found;
    }
}
