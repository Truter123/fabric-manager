package dev.kamil.fabric.bodydouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoDownloaderTest {

    private static final String URL = "https://www.youtube.com/watch?v=ddoK6cYgrvo";

    @TempDir
    Path cache;

    private VideoDownloader downloader(VideoDownloader.Runner runner, List<String> runtime) {
        return new VideoDownloader(runner, cache, List.of("yt-dlp", "/snap/bin/yt-dlp"), runtime);
    }

    @Test
    void extractsVideoIds() {
        assertEquals("ddoK6cYgrvo", VideoDownloader.videoId(URL));
        assertEquals("ddoK6cYgrvo", VideoDownloader.videoId("https://www.youtube.com/watch?list=x&v=ddoK6cYgrvo&t=5"));
        assertEquals("ddoK6cYgrvo", VideoDownloader.videoId("https://youtu.be/ddoK6cYgrvo?t=3"));
        assertEquals("ddoK6cYgrvo", VideoDownloader.videoId("https://www.youtube.com/shorts/ddoK6cYgrvo"));
        assertNull(VideoDownloader.videoId("https://example.com/watch?v=ddoK6cYgrvo"));
        assertNull(VideoDownloader.videoId("not a url"));
    }

    @Test
    void buildsDownloadCommandWithRuntime() throws Exception {
        var commands = new ArrayList<List<String>>();
        var d = downloader((command, timeout, onLine, cancelled) -> {
            commands.add(command);
            try {
                Files.writeString(cache.resolve("ddoK6cYgrvo.mp4"), "x");
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            return new VideoDownloader.Result(0, "", "");
        }, List.of("--js-runtimes", "node:/opt/node"));

        var file = d.fetch(URL, p -> { }, () -> false);

        var c = commands.get(0);
        assertEquals(cache.resolve("ddoK6cYgrvo.mp4"), file);
        assertEquals("yt-dlp", c.get(0));
        assertEquals(List.of("--js-runtimes", "node:/opt/node"), c.subList(1, 3));
        assertEquals(VideoDownloader.FORMAT, c.get(c.indexOf("-f") + 1));
        assertEquals("mp4", c.get(c.indexOf("--merge-output-format") + 1));
        assertEquals(cache.resolve("ddoK6cYgrvo.%(ext)s").toString(), c.get(c.indexOf("-o") + 1));
        assertEquals(URL, c.get(c.size() - 1));
    }

    @Test
    void omitsRuntimeWhenNodeAbsent() throws Exception {
        var commands = new ArrayList<List<String>>();
        var d = downloader((command, timeout, onLine, cancelled) -> {
            commands.add(command);
            return new VideoDownloader.Result(0, "Title\n", "");
        }, List.of());

        assertEquals("Title", d.title(URL));
        assertFalse(commands.get(0).contains("--js-runtimes"));
        assertTrue(commands.get(0).contains("--get-title"));
    }

    @Test
    void cacheHitSkipsRunner() throws Exception {
        Files.writeString(cache.resolve("ddoK6cYgrvo.mp4"), "x");
        var d = downloader((command, timeout, onLine, cancelled) -> {
            throw new AssertionError("runner must not be called");
        }, List.of());

        assertEquals(cache.resolve("ddoK6cYgrvo.mp4"), d.fetch(URL, p -> { }, () -> false));
        d.deleteCached(URL);
        assertTrue(d.cached(URL).isEmpty());
    }

    @Test
    void reportsProgressOncePerPercent() throws Exception {
        var seen = new ArrayList<Integer>();
        var d = downloader((command, timeout, onLine, cancelled) -> {
            onLine.accept("[info] x");
            onLine.accept("[download]   0.5% of 150.00MiB at 1MiB/s");
            onLine.accept("[download]   0.9% of 150.00MiB at 1MiB/s");
            onLine.accept("[download]  37.0% of 150.00MiB at 1MiB/s");
            try {
                Files.writeString(cache.resolve("ddoK6cYgrvo.mp4"), "x");
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            return new VideoDownloader.Result(0, "", "");
        }, List.of());

        d.fetch(URL, seen::add, () -> false);

        assertEquals(List.of(0, 37), seen);
        assertEquals(-1, VideoDownloader.parsePercent("[Merger] Merging"));
    }

    @Test
    void triesNextBinaryWhenFirstCannotStart() throws Exception {
        var d = downloader((command, timeout, onLine, cancelled) -> {
            if (command.get(0).equals("yt-dlp")) {
                throw new IOException("not found");
            }
            return new VideoDownloader.Result(0, "T\n", "");
        }, List.of());

        assertEquals("T", d.title(URL));
    }

    @Test
    void missingBinaryGivesClearMessage() {
        var d = downloader((command, timeout, onLine, cancelled) -> {
            throw new IOException("Cannot run program");
        }, List.of());

        var e = assertThrows(VideoDownloader.ResolveException.class, () -> d.fetch(URL, p -> { }, () -> false));
        assertTrue(e.getMessage().contains("yt-dlp not found"));
    }

    @Test
    void failureKeepsLastLineAndAddsUpdateHintOn403() {
        var d = downloader((command, timeout, onLine, cancelled) ->
                new VideoDownloader.Result(1, "", "WARNING x\nERROR: HTTP Error 403: Forbidden\n"), List.of());

        var e = assertThrows(VideoDownloader.ResolveException.class, () -> d.fetch(URL, p -> { }, () -> false));
        assertTrue(e.getMessage().startsWith("yt-dlp failed: ERROR: HTTP Error 403: Forbidden"));
        assertTrue(e.getMessage().contains(VideoDownloader.UPDATE_HINT));
    }

    @Test
    void otherFailureHasNoHint() {
        var d = downloader((command, timeout, onLine, cancelled) ->
                new VideoDownloader.Result(1, "", "ERROR: Video unavailable\n"), List.of());

        var e = assertThrows(VideoDownloader.ResolveException.class, () -> d.fetch(URL, p -> { }, () -> false));
        assertEquals("yt-dlp failed: ERROR: Video unavailable", e.getMessage());
    }

    @Test
    void formatUnavailableAddsHint() {
        var d = downloader((command, timeout, onLine, cancelled) ->
                new VideoDownloader.Result(1, "", "ERROR: Requested format is not available\n"), List.of());

        var e = assertThrows(VideoDownloader.ResolveException.class, () -> d.fetch(URL, p -> { }, () -> false));
        assertTrue(e.getMessage().contains(VideoDownloader.UPDATE_HINT));
    }

    @Test
    void titleIsNullWhenToolFails() {
        var d = downloader((command, timeout, onLine, cancelled) -> new VideoDownloader.Result(1, "", "boom"), List.of());

        assertNull(d.title(URL));
    }
}
