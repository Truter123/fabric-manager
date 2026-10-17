package dev.kamil.fabric.english;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VoiceTest {

    private Path savedCache;

    @TempDir
    Path dir;

    @BeforeEach
    void prepare() {
        savedCache = Voice.cacheDir;
        Voice.cacheDir = dir.resolve("tts");
        Voice.fake = true;
        Voice.LOG.clear();
    }

    @AfterEach
    void restore() {
        Voice.cacheDir = savedCache;
        Voice.fake = false;
    }

    @Test
    void keyIsTheSha1OfTheTextWithASlowPrefix() {
        // sha1("abc") and sha1("slow:abc")
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Voice.key("abc", false));
        assertEquals(Voice.key("slow:abc", false), Voice.key("abc", true));
        assertNotEquals(Voice.key("abc", false), Voice.key("abc", true));
    }

    @Test
    void wavIsNamedByTheKeyAndReusedFromTheCache() throws Exception {
        var first = Voice.wav("Hello there", false);
        assertEquals(Voice.cacheDir.resolve(Voice.key("Hello there", false) + ".wav"), first);
        assertTrue(Files.size(first) > 0);
        var again = Voice.wav("Hello there", false);
        assertEquals(first, again);
        assertEquals(List.of("voice:normal:Hello there"), List.copyOf(Voice.LOG));

        var slow = Voice.wav("Hello there", true);
        assertNotEquals(first, slow);
        assertEquals(2, Voice.LOG.size());
        assertEquals("voice:slow:Hello there", Voice.LOG.get(1));
        // only the two finished files remain, no temp leftovers
        try (var files = Files.list(Voice.cacheDir)) {
            assertEquals(2, files.count());
        }
    }
}
