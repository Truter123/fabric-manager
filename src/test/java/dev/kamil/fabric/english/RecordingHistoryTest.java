package dev.kamil.fabric.english;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingHistoryTest {

    @TempDir
    Path root;

    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 16, 14, 2, 5);

    private Path save(RecordingHistory h, String english, LocalDateTime at) throws IOException {
        var f = h.newFile(english, at);
        Files.writeString(f, "x");
        return f;
    }

    @Test
    void sameEnglishSameDirDifferentDiffers() throws IOException {
        var h = new RecordingHistory(root);
        var a = h.newFile("Hello there", T);
        var b = h.newFile(" Hello there ", T.plusSeconds(1));
        var c = h.newFile("Other", T);
        assertEquals(a.getParent(), b.getParent());
        assertNotEquals(a.getParent(), c.getParent());
        assertEquals(12, a.getParent().getFileName().toString().length());
    }

    @Test
    void sameSecondRecordingsDoNotOverwriteAndStayOrdered() throws IOException {
        var h = new RecordingHistory(root);
        var a = save(h, "Hi", T);
        var b = save(h, "Hi", T);
        var c = save(h, "Hi", T);
        assertEquals(3, java.util.Set.of(a, b, c).size());
        var list = h.list("Hi");
        assertEquals(3, list.size());
        assertEquals(c, list.get(0).file());
        assertEquals(a, list.get(2).file());
        assertEquals(T, list.get(2).at());
        assertEquals(RecordingHistory.label(T), RecordingHistory.label(list.get(0).at()));
    }

    @Test
    void listsNewestFirstWithNames() throws IOException {
        var h = new RecordingHistory(root);
        save(h, "Hi", T);
        save(h, "Hi", T.plusMinutes(5));
        var list = h.list("Hi");
        assertEquals(2, list.size());
        assertEquals(T.plusMinutes(5), list.get(0).at());
        assertEquals("2026-10-16_14-02-05.wav", list.get(1).file().getFileName().toString());
        assertEquals(2, h.count("Hi"));
        assertEquals(0, h.count("None"));
        assertTrue(h.list("None").isEmpty());
    }

    @Test
    void pruneKeepsNewest() throws IOException {
        var h = new RecordingHistory(root);
        for (int i = 0; i < 5; i++) {
            save(h, "Hi", T.plusMinutes(i));
        }
        h.prune("Hi", 2);
        var list = h.list("Hi");
        assertEquals(2, list.size());
        assertEquals(T.plusMinutes(4), list.get(0).at());
        assertEquals(T.plusMinutes(3), list.get(1).at());
    }

    @Test
    void deleteRemovesFile() throws IOException {
        var h = new RecordingHistory(root);
        var f = save(h, "Hi", T);
        h.delete(f);
        assertFalse(Files.exists(f));
        assertEquals(0, h.count("Hi"));
    }

    @Test
    void writesSentenceText() throws IOException {
        var h = new RecordingHistory(root);
        var f = h.newFile("Hi there", T);
        assertEquals("Hi there\n", Files.readString(f.getParent().resolve("sentence.txt")));
    }
}
