package dev.kamil.fabric.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranscriptSearchTest {

    @TempDir
    Path home;

    private Path write(String project, String id, long mtime, String... lines) throws IOException {
        var dir = Files.createDirectories(home.resolve("projects").resolve(project));
        var file = dir.resolve(id + ".jsonl");
        Files.writeString(file, String.join("\n", lines) + "\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtime));
        return file;
    }

    private static String user(String text) {
        return "{\"type\":\"user\",\"cwd\":\"/work/app\",\"timestamp\":\"2026-01-02T03:04:05Z\","
                + "\"message\":{\"role\":\"user\",\"content\":\"" + text + "\"}}";
    }

    private static String assistant(String text) {
        return "{\"type\":\"assistant\",\"cwd\":\"/work/app\",\"timestamp\":\"2026-01-02T03:04:06Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\"},"
                + "{\"type\":\"text\",\"text\":\"" + text + "\"}]}}";
    }

    @Test
    void matchesUserText() throws IOException {
        write("p", "s1", 1000, user("please refactor the parser"));
        var hits = TranscriptSearch.search(List.of(home), "refactor", 10);
        assertEquals(1, hits.size());
        var hit = hits.get(0);
        assertEquals("s1", hit.sessionId());
        assertEquals("user", hit.role());
        assertEquals(Path.of("/work/app"), hit.project());
        assertEquals(home, hit.claudeHome());
        assertEquals("2026-01-02T03:04:05Z", hit.time().toString());
        assertTrue(hit.snippet().contains("refactor"));
    }

    @Test
    void matchesAssistantArrayContent() throws IOException {
        write("p", "s1", 1000, assistant("The fix is in the Lexer"));
        var hits = TranscriptSearch.search(List.of(home), "lexer", 10);
        assertEquals(1, hits.size());
        assertEquals("assistant", hits.get(0).role());
    }

    @Test
    void isCaseInsensitive() throws IOException {
        write("p", "s1", 1000, user("Hello WORLD"));
        assertEquals(1, TranscriptSearch.search(List.of(home), "world", 10).size());
    }

    @Test
    void noMatchAndBlankQueryGiveNothing() throws IOException {
        write("p", "s1", 1000, user("hello"));
        assertTrue(TranscriptSearch.search(List.of(home), "absent", 10).isEmpty());
        assertTrue(TranscriptSearch.search(List.of(home), "  ", 10).isEmpty());
    }

    @Test
    void skipsMalformedLines() throws IOException {
        write("p", "s1", 1000, "{not json", user("needle here"));
        assertEquals(1, TranscriptSearch.search(List.of(home), "needle", 10).size());
    }

    @Test
    void respectsLimitNewestFirst() throws IOException {
        write("p", "old", 1000, user("needle"));
        write("p", "mid", 2000, user("needle"));
        write("q", "new", 3000, user("needle"));
        var hits = TranscriptSearch.search(List.of(home), "needle", 2);
        assertEquals(List.of("new", "mid"), hits.stream().map(TranscriptSearch.Hit::sessionId).toList());
    }

    @Test
    void oneHitPerSession() throws IOException {
        write("p", "s1", 1000, user("needle one"), user("needle two"));
        var hits = TranscriptSearch.search(List.of(home), "needle", 10);
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).snippet().contains("one"));
    }
}
