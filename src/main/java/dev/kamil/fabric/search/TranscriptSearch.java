package dev.kamil.fabric.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Case-insensitive substring search over Claude transcripts ({@code <home>/projects/*}{@code /<id>.jsonl}). */
public final class TranscriptSearch {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CONTEXT = 60;

    private TranscriptSearch() {
    }

    /** A matching session: the first user or assistant message containing the query. */
    public record Hit(Path claudeHome, String sessionId, Path project, Instant time, String role, String snippet) {
    }

    /** One hit per session, newest transcript first, at most {@code limit}; a blank query finds nothing. */
    public static List<Hit> search(List<Path> claudeHomes, String query, int limit) {
        var hits = new ArrayList<Hit>();
        if (query == null || query.isBlank() || limit <= 0) {
            return hits;
        }
        var needle = query.strip().toLowerCase(Locale.ROOT);
        for (var file : transcripts(claudeHomes)) {
            var hit = scan(file, needle);
            if (hit != null) {
                hits.add(hit);
                if (hits.size() >= limit) {
                    break;
                }
            }
        }
        return hits;
    }

    private record Transcript(Path home, Path file, long modified) {
    }

    private static List<Transcript> transcripts(List<Path> homes) {
        var all = new ArrayList<Transcript>();
        for (var home : homes) {
            var projects = home.resolve("projects");
            if (!Files.isDirectory(projects)) {
                continue;
            }
            try (var dirs = Files.newDirectoryStream(projects, Files::isDirectory)) {
                for (var dir : dirs) {
                    try (var files = Files.newDirectoryStream(dir, "*.jsonl")) {
                        for (var file : files) {
                            all.add(new Transcript(home, file, Files.getLastModifiedTime(file).toMillis()));
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("Cannot list transcripts in " + projects + ": " + e.getMessage());
            }
        }
        all.sort(Comparator.comparingLong(Transcript::modified).reversed());
        return all;
    }

    private static Hit scan(Transcript transcript, String needle) {
        var name = transcript.file().getFileName().toString();
        var sessionId = name.substring(0, name.length() - ".jsonl".length());
        try (var lines = Files.lines(transcript.file())) {
            var it = lines.iterator();
            while (it.hasNext()) {
                var hit = match(transcript, sessionId, it.next(), needle);
                if (hit != null) {
                    return hit;
                }
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            System.err.println("Cannot read " + transcript.file() + ": " + e.getMessage());
        }
        return null;
    }

    private static Hit match(Transcript transcript, String sessionId, String line, String needle) {
        if (line.isBlank()) {
            return null;
        }
        JsonNode node;
        try {
            node = JSON.readTree(line);
        } catch (IOException e) {
            return null;
        }
        if (node == null || !node.isObject()) {
            return null;
        }
        var message = node.path("message");
        var role = message.path("role").asText(node.path("type").asText(""));
        if (!role.equals("user") && !role.equals("assistant")) {
            return null;
        }
        var text = text(message.path("content"));
        int at = text.toLowerCase(Locale.ROOT).indexOf(needle);
        if (at < 0) {
            return null;
        }
        var cwd = node.path("cwd").asText("");
        var project = cwd.isEmpty() ? null : Path.of(cwd);
        return new Hit(transcript.home(), sessionId, project, time(node.path("timestamp").asText("")), role,
                snippet(text, at, needle.length()));
    }

    private static String text(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        var sb = new StringBuilder();
        if (content.isArray()) {
            for (var part : content) {
                if ("text".equals(part.path("type").asText()) && part.path("text").isTextual()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(part.path("text").asText());
                }
            }
        }
        return sb.toString();
    }

    private static Instant time(String stamp) {
        try {
            return stamp.isEmpty() ? null : Instant.parse(stamp);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static String snippet(String text, int at, int length) {
        int from = Math.max(0, at - CONTEXT);
        int to = Math.min(text.length(), at + length + CONTEXT);
        var cut = text.substring(from, to).replaceAll("\\s+", " ").strip();
        return (from > 0 ? "..." : "") + cut + (to < text.length() ? "..." : "");
    }
}
