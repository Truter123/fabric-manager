package dev.kamil.fabric.claude.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranscriptMarkdownTest {

    private static final Path FIXTURE = Path.of("src/test/resources/usage/fuse.jsonl");

    @Test
    void rendersTextAndToolLineAndDropsNoise() throws IOException {
        var md = TranscriptMarkdown.render(Files.readAllLines(FIXTURE), "My Session", Path.of("/work"));
        assertTrue(md.startsWith("# Transcript of session \"My Session\" (/work)\n\n"));
        assertTrue(md.contains("## User\n\nHello there"));
        assertTrue(md.contains("## Claude\n\nHi, how can I help?"));
        assertTrue(md.contains("> tool: Read"));
        assertFalse(md.contains("TOOL-OUTPUT"));
        assertFalse(md.contains("SECRET-THOUGHT"));
        assertFalse(md.contains("META-NOISE"));
    }

    @Test
    void exportWritesSlugNamedFile(@TempDir Path dir) throws IOException {
        var out = TranscriptMarkdown.export(FIXTURE, dir.resolve("nested"), "My Session!", Path.of("/work"));
        assertTrue(Files.isRegularFile(out));
        assertTrue(out.getFileName().toString().matches("my-session-\\d{8}-\\d{6}\\.md"));
        assertTrue(Files.readString(out).contains("Hello there"));
    }

    @Test
    void slugFallsBackToSession() {
        assertEquals("session", TranscriptMarkdown.slug("!!!"));
    }
}
