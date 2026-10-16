package dev.kamil.fabric.claude.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Renders a Claude Code transcript (JSONL) as Markdown: user and assistant text plus one line per tool call. */
public final class TranscriptMarkdown {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private TranscriptMarkdown() {
    }

    public static String render(List<String> jsonlLines, String title, Path cwd) {
        var out = new StringBuilder();
        out.append("# Transcript of session \"").append(title).append("\" (").append(cwd).append(")\n\n");
        for (var line : jsonlLines) {
            var root = parse(line);
            if (root == null) {
                continue;
            }
            var type = root.path("type").asText("");
            if (!"user".equals(type) && !"assistant".equals(type)) {
                continue;
            }
            if (root.path("isSidechain").asBoolean(false) || root.path("isMeta").asBoolean(false)) {
                continue;
            }
            var body = body(root.path("message").path("content"));
            if (body.isBlank()) {
                continue;
            }
            out.append("user".equals(type) ? "## User\n\n" : "## Claude\n\n").append(body).append("\n\n");
        }
        return out.toString();
    }

    public static Path export(Path transcript, Path outDir, String title, Path cwd) throws IOException {
        var lines = Files.readAllLines(transcript, StandardCharsets.UTF_8);
        Files.createDirectories(outDir);
        var file = outDir.resolve(slug(title) + "-" + LocalDateTime.now().format(STAMP) + ".md");
        Files.writeString(file, render(lines, title, cwd), StandardCharsets.UTF_8);
        return file;
    }

    public static Path defaultDir() {
        return Path.of(System.getProperty("user.home"), ".local", "share", "fabric-manager", "fusions");
    }

    private static JsonNode parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(line);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String body(JsonNode content) {
        if (content.isTextual()) {
            return content.asText().strip();
        }
        if (!content.isArray()) {
            return "";
        }
        var parts = new ArrayList<String>();
        for (var block : content) {
            var kind = block.path("type").asText("");
            if ("text".equals(kind)) {
                var text = block.path("text").asText("").strip();
                if (!text.isEmpty()) {
                    parts.add(text);
                }
            } else if ("tool_use".equals(kind)) {
                parts.add("> tool: " + block.path("name").asText(""));
            }
        }
        return String.join("\n\n", parts);
    }

    static String slug(String title) {
        var s = title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "session" : s;
    }
}
