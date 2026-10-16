package dev.kamil.fabric.bodydouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoCacheTest {

    @TempDir
    Path dir;

    private Path file(String name, int bytes, long modified) throws Exception {
        var file = dir.resolve(name);
        Files.write(file, new byte[bytes]);
        Files.setLastModifiedTime(file, FileTime.fromMillis(modified));
        return file;
    }

    @Test
    void clearRemovesEveryFileAndReportsTheBytes() throws Exception {
        file("a.mp4", 10, 1000);
        file("b.mp4", 20, 2000);
        assertEquals(30, VideoDownloader.clearCache(dir));
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void trimDropsTheOldestFirstAndKeepsTheNewDownload() throws Exception {
        var old = file("old.mp4", 10, 1000);
        var mid = file("mid.mp4", 10, 2000);
        var fresh = file("fresh.mp4", 10, 500);
        VideoDownloader.trimCache(dir, 15, fresh);
        assertFalse(Files.exists(old));
        assertFalse(Files.exists(mid));
        assertTrue(Files.exists(fresh));
    }
}
