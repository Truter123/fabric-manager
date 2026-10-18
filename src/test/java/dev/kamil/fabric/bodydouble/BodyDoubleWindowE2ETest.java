package dev.kamil.fabric.bodydouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.EscapeClosesWindowsE2ETest;
import dev.kamil.fabric.FxHarness;
import dev.kamil.fabric.session.Database;
import dev.kamil.fabric.settings.AppSettings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.Arguments;

/** Drives the real body double window with a stubbed yt-dlp and a generated clip; skipped without a display. */
class BodyDoubleWindowE2ETest {

    private static final String URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    private static final String URL2 = "https://youtu.be/abcdefghijk";
    private static final ByteArrayOutputStream ERR = new ByteArrayOutputStream();
    private static PrintStream realErr;
    private static Path clip;

    @FunctionalInterface
    interface Download {
        VideoDownloader.Result run(List<String> command, Consumer<String> onLine) throws IOException;
    }

    @TempDir
    Path dir;

    private Database db;
    private BodyDoubleStore store;
    private Path cache;
    private AppSettings previousSettings;
    private final List<List<String>> commands = Collections.synchronizedList(new ArrayList<>());
    private volatile String title = "Stub title";
    private volatile Download download;

    @BeforeAll
    static void startFx() throws Exception {
        Assumptions.assumeTrue(FxHarness.displayAvailable(),
                "no display");
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // another test class started it
        }
        Platform.setImplicitExit(false);
        if (realErr == null) {
            realErr = System.err;
            System.setErr(new PrintStream(new java.io.OutputStream() {
                @Override
                public void write(int b) {
                    realErr.write(b);
                    synchronized (ERR) {
                        ERR.write(b);
                    }
                }
            }, true));
        }
        var tmp = Files.createTempDirectory("bodydouble-clip");
        // The first candidate this machine's JavaFX media can decode; the file keeps the .mp4 name the cache uses.
        List<List<String>> candidates = List.of(
                List.of("-f", "lavfi", "-i", "testsrc=d=6:s=160x120:r=10", "-f", "lavfi", "-i", "sine=d=6", "-c:v",
                        "libx264", "-pix_fmt", "yuv420p", "-profile:v", "baseline", "-c:a", "aac", "-movflags",
                        "+faststart", "-shortest", "-f", "mp4"),
                List.of("-f", "lavfi", "-i", "sine=d=6", "-c:a", "libmp3lame", "-f", "mp3"),
                List.of("-f", "lavfi", "-i", "sine=d=6", "-c:a", "pcm_s16le", "-f", "wav"));
        for (var args : candidates) {
            var file = tmp.resolve("clip.mp4");
            var command = new ArrayList<>(List.of("ffmpeg", "-loglevel", "error", "-y"));
            command.addAll(args.subList(0, args.size() - 2));
            command.addAll(List.of("-f", args.get(args.size() - 1), file.toString()));
            try {
                var p = new ProcessBuilder(command).inheritIO().start();
                if (p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0 && canPlay(file)) {
                    clip = file;
                    return;
                }
            } catch (IOException e) {
                return;
            }
        }
    }

    private static boolean canPlay(Path file) throws Exception {
        var ready = new CompletableFuture<Boolean>();
        Platform.runLater(() -> {
            try {
                var player = new MediaPlayer(new javafx.scene.media.Media(file.toUri().toString()));
                player.setOnReady(() -> {
                    ready.complete(true);
                    player.dispose();
                });
                player.setOnError(() -> ready.complete(false));
            } catch (RuntimeException e) {
                ready.complete(false);
            }
        });
        try {
            return ready.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return false;
        }
    }

    @BeforeEach
    void open() throws Exception {
        previousSettings = AppSettings.shared();
        AppSettings.use(AppSettings.inMemory());
        db = Database.at(dir.resolve("e2e")).migrate();
        store = new BodyDoubleStore(db);
        cache = dir.resolve("cache");
        download = this::copyClip;
        BodyDoubleWindow.downloaders = () -> new VideoDownloader((command, timeout, onLine, cancelled) -> {
            commands.add(command);
            if (command.contains("--get-title")) {
                return new VideoDownloader.Result(0, title + "\n", "");
            }
            return download.run(command, onLine);
        }, cache, List.of("stub-yt-dlp"), List.of());
        synchronized (ERR) {
            ERR.reset();
        }
    }

    @AfterEach
    void close() throws Exception {
        fx(() -> {
            var s = BodyDoubleWindow.openStage();
            if (s != null) {
                s.hide();
            }
            return null;
        });
        BodyDoubleWindow.downloaders = VideoDownloader::new;
        AppSettings.use(previousSettings);
        String err;
        synchronized (ERR) {
            err = ERR.toString();
        }
        assertFalse(err.contains("Exception"), "exception on stderr: " + err);
    }

    /** Stub download: reports progress, then places the generated clip where yt-dlp would write it. */
    private VideoDownloader.Result copyClip(List<String> command, Consumer<String> onLine) throws IOException {
        onLine.accept("[download]  40.0% of 1MiB");
        var out = command.get(command.indexOf("-o") + 1).replace("%(ext)s", "mp4");
        Files.createDirectories(Path.of(out).getParent());
        Files.copy(clip, Path.of(out), StandardCopyOption.REPLACE_EXISTING);
        onLine.accept("[download] 100.0% of 1MiB");
        return new VideoDownloader.Result(0, "", "");
    }

    private void needsClip() {
        Assumptions.assumeTrue(clip != null && Files.exists(clip),
                "JavaFX media cannot play a generated clip here (no ffmpeg, or no usable audio/codec stack)");
    }

    /** The case of {@link EscapeClosesWindowsE2ETest}: in-memory settings and a downloader that is never asked. */
    static Stream<Arguments> escapeCase() {
        var previous = new AtomicReference<AppSettings>();
        return Stream.of(EscapeClosesWindowsE2ETest.escapeCase("Body double", dir -> {
            previous.set(AppSettings.shared());
            AppSettings.use(AppSettings.inMemory());
            BodyDoubleWindow.downloaders = () -> new VideoDownloader(
                    (command, timeout, onLine, cancelled) -> new VideoDownloader.Result(1, "", "unused"),
                    dir.resolve("cache"), List.of("stub-yt-dlp"), List.of());
            var db = Database.at(dir.resolve("escape")).migrate();
            return FxHarness.fx(() -> {
                BodyDoubleWindow.toggle(null, db);
                return BodyDoubleWindow.openStage();
            });
        }, () -> {
            FxHarness.fx(() -> {
                var stage = BodyDoubleWindow.openStage();
                if (stage != null) {
                    stage.hide();
                }
                return null;
            });
            BodyDoubleWindow.downloaders = VideoDownloader::new;
            if (previous.get() != null) {
                AppSettings.use(previous.get());
            }
        }));
    }

    // ---- harness

    private static <T> T fx(Callable<T> action) throws Exception {
        var future = new CompletableFuture<T>();
        Platform.runLater(() -> {
            try {
                future.complete(action.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.get(10, TimeUnit.SECONDS);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < end) {
            if (fx(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("condition not reached");
    }

    private void show() throws Exception {
        fx(() -> {
            BodyDoubleWindow.toggle(null, db);
            return null;
        });
        fx(() -> null);
    }

    private static Stage stage() {
        return BodyDoubleWindow.openStage();
    }

    private static Parent root() {
        return stage().getScene().getRoot();
    }

    private static <T extends Node> T find(Node node, Class<T> type) {
        if (type.isInstance(node)) {
            return type.cast(node);
        }
        if (node instanceof Parent p) {
            for (var child : p.getChildrenUnmodifiable()) {
                var hit = find(child, type);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static List<Node> all(Node node, List<Node> out) {
        out.add(node);
        if (node instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> all(c, out));
        }
        return out;
    }

    private static Button button(String text) {
        return all(root(), new ArrayList<>()).stream().filter(n -> n instanceof Button b && text.equals(b.getText()))
                .map(n -> (Button) n).findFirst().orElseThrow(() -> new AssertionError("no button " + text));
    }

    private static ToggleButton toggle(String text) {
        return all(root(), new ArrayList<>()).stream().filter(n -> n instanceof ToggleButton b && text.equals(b.getText()))
                .map(n -> (ToggleButton) n).findFirst().orElseThrow();
    }

    private static List<Slider> sliders() {
        return all(root(), new ArrayList<>()).stream().filter(n -> n instanceof Slider).map(n -> (Slider) n).toList();
    }

    private static TextField url() {
        return find(root(), TextField.class);
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<Video> recent() {
        return find(root(), ComboBox.class);
    }

    private static MediaPlayer player() {
        return find(root(), MediaView.class).getMediaPlayer();
    }

    private static String statusText() {
        // the status label sits over the video in the center stack
        return all(root(), new ArrayList<>()).stream().filter(n -> n instanceof Label l && l.getStyleClass().contains("empty-hint"))
                .map(n -> ((Label) n).getText()).findFirst().orElseThrow();
    }

    private String status() throws Exception {
        return fx(BodyDoubleWindowE2ETest::statusText);
    }

    private void enter(String text) throws Exception {
        fx(() -> {
            url().setText(text);
            url().fireEvent(new ActionEvent());
            return null;
        });
    }

    private void awaitPlaying() throws Exception {
        try {
            await(() -> player() != null && player().getStatus() == MediaPlayer.Status.PLAYING);
        } catch (AssertionError e) {
            throw new AssertionError("not playing; status label: " + status() + "; player: "
                    + fx(() -> player() == null ? null : player().getStatus() + " " + player().getError()), e);
        }
    }

    private void key(KeyCode code) throws Exception {
        fx(() -> {
            stage().getScene().getRoot().requestFocus();
            Event.fireEvent(root(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
            return null;
        });
    }

    // ---- tests

    @Test
    void togglesOpenAndClosed() throws Exception {
        show();
        assertNotNull(fx(BodyDoubleWindowE2ETest::stage));
        assertEquals("Body double", fx(() -> stage().getTitle()));
        assertEquals("Paste a link above.", status());
        assertEquals("Paste a YouTube link, press Enter", fx(() -> url().getPromptText()));
        assertEquals(0, fx(() -> recent().getItems().size()).intValue());
        assertTrue(fx(() -> stage().isAlwaysOnTop()));
        fx(() -> {
            BodyDoubleWindow.toggle(null, db);
            return null;
        });
        assertNull(fx(BodyDoubleWindow::openStage));
        show();
        assertNotNull(fx(BodyDoubleWindow::openStage));
    }

    @Test
    void controlsWithoutAPlayerAreHarmless() throws Exception {
        show();
        fx(() -> {
            button("❚❚").fire();
            button("◀◀").fire();
            button("▶▶").fire();
            toggle("♪").fire();
            toggle("↻").fire();
            toggle("↻").fire();
            sliders().get(0).setValue(10);
            return null;
        });
        key(KeyCode.SPACE);
        key(KeyCode.LEFT);
        key(KeyCode.RIGHT);
        assertEquals("Paste a link above.", status());
        assertTrue(fx(() -> toggle("↻").isSelected()));
    }

    @Test
    void keysTypedInTheLinkFieldDoNotControlThePlayer() throws Exception {
        show();
        fx(() -> {
            url().requestFocus();
            url().setText("abc");
            return null;
        });
        fx(() -> {
            Event.fireEvent(url(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.SPACE, false, false, false, false));
            return null;
        });
        assertEquals("abc", fx(() -> url().getText()));
    }

    @Test
    void blankLinkDoesNothing() throws Exception {
        show();
        enter("   ");
        fx(() -> null);
        assertEquals("Paste a link above.", status());
        assertEquals(0, store.recent(10).size());
        assertTrue(commands.isEmpty());
    }

    @Test
    void notAYouTubeLinkIsStoredAndReported() throws Exception {
        show();
        enter("https://example.com/not-a-video");
        await(() -> "Not a YouTube video link".equals(statusText()));
        assertEquals(1, store.recent(10).size());
        // the title lookup runs on the side and the list then shows it
        await(() -> recent().getItems().size() == 1 && "Stub title".equals(recent().getItems().get(0).label()));
        assertEquals("Stub title", store.find("https://example.com/not-a-video").title());
    }

    @Test
    void downloadShowsProgressThenPlays() throws Exception {
        needsClip();
        var gate = new CountDownLatch(1);
        download = (command, onLine) -> {
            onLine.accept("[download]  40.0% of 1MiB");
            try {
                gate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            return copyClip(command, onLine);
        };
        show();
        enter(URL);
        await(() -> "Downloading… 40%".equals(statusText()));
        gate.countDown();
        awaitPlaying();
        assertEquals("", status());
        assertEquals(VideoDownloaderPause.PAUSE, fx(() -> button(VideoDownloaderPause.PAUSE).getText()));
        assertTrue(Files.exists(cache.resolve("dQw4w9WgXcQ.mp4")));
        assertEquals(URL, fx(() -> recent().getItems().get(0).url()));
    }

    @Test
    void playPauseButtonAndSpaceToggleThePlayer() throws Exception {
        needsClip();
        show();
        enter(URL);
        awaitPlaying();
        fx(() -> {
            button("❚❚").fire();
            return null;
        });
        await(() -> player().getStatus() == MediaPlayer.Status.PAUSED);
        assertEquals("▶", fx(() -> button("▶").getText()));
        key(KeyCode.SPACE);
        awaitPlaying();
        key(KeyCode.SPACE);
        await(() -> player().getStatus() == MediaPlayer.Status.PAUSED);
    }

    @Test
    void seekButtonsAndArrowKeysMoveByFiveSeconds() throws Exception {
        needsClip();
        show();
        enter(URL);
        awaitPlaying();
        fx(() -> {
            button("❚❚").fire();
            return null;
        });
        await(() -> player().getStatus() == MediaPlayer.Status.PAUSED);
        fx(() -> {
            player().seek(javafx.util.Duration.seconds(0.5));
            return null;
        });
        await(() -> Math.abs(player().getCurrentTime().toSeconds() - 0.5) < 0.3);
        fx(() -> {
            button("▶▶").fire();
            return null;
        });
        await(() -> player().getCurrentTime().toSeconds() > 4.5);
        fx(() -> {
            button("◀◀").fire();
            return null;
        });
        await(() -> player().getCurrentTime().toSeconds() < 1.5);
        key(KeyCode.RIGHT);
        await(() -> player().getCurrentTime().toSeconds() > 4.5);
        key(KeyCode.LEFT);
        await(() -> player().getCurrentTime().toSeconds() < 1.5);
    }

    @Test
    void muteLoopAndVolumeControlsReachThePlayer() throws Exception {
        needsClip();
        show();
        enter(URL);
        awaitPlaying();
        assertTrue(fx(() -> toggle("↻").isSelected()));
        assertEquals(MediaPlayer.INDEFINITE, fx(() -> player().getCycleCount()).intValue());
        fx(() -> {
            toggle("↻").fire();
            return null;
        });
        assertEquals(1, fx(() -> player().getCycleCount()).intValue());
        fx(() -> {
            toggle("♪").fire();
            return null;
        });
        assertTrue(fx(() -> player().isMute()));
        fx(() -> {
            toggle("♪").fire();
            return null;
        });
        assertFalse(fx(() -> player().isMute()));
        fx(() -> {
            sliders().get(0).setValueChanging(true);
            sliders().get(0).setValue(35);
            sliders().get(0).setValueChanging(false);
            return null;
        });
        assertEquals(0.35, fx(() -> player().getVolume()), 0.001);
        assertEquals(35, AppSettings.shared().getInt(BodyDoubleWindow.VOLUME_KEY, -1));
    }

    @Test
    void volumeIsRestoredAndOpacitySliderSetsWindowOpacity() throws Exception {
        AppSettings.shared().setInt(BodyDoubleWindow.VOLUME_KEY, 30);
        show();
        assertEquals(30.0, fx(() -> sliders().get(0).getValue()), 0.001);
        assertEquals(1.0, fx(() -> stage().getOpacity()), 0.001);
        fx(() -> {
            sliders().get(1).setValue(0.5);
            return null;
        });
        assertEquals(0.5, fx(() -> stage().getOpacity()), 0.01);
    }

    @Test
    void recentListReloadsAVideoAndKeepsTheNewestTen() throws Exception {
        needsClip();
        for (int i = 0; i < 12; i++) {
            store.upsert("https://example.com/v" + i, "T" + i);
        }
        show();
        assertEquals(10, fx(() -> recent().getItems().size()).intValue());
        assertEquals("T11", fx(() -> recent().getItems().get(0).label()));
        fx(() -> {
            recent().getSelectionModel().select(recent().getItems().get(2));
            return null;
        });
        await(() -> "https://example.com/v9".equals(url().getText()));
        await(() -> "Not a YouTube video link".equals(statusText()));
        assertNull(fx(() -> recent().getValue()));
        // playing it moved it to the top of the list
        assertEquals("T9", fx(() -> recent().getItems().get(0).label()));
    }

    @Test
    void recentPopupRendersTheTitles() throws Exception {
        store.upsert("https://example.com/a", "Alpha");
        store.upsert("https://example.com/b", null);
        show();
        fx(() -> {
            recent().show();
            return null;
        });
        await(() -> {
            for (var w : javafx.stage.Window.getWindows()) {
                if (w.isShowing() && w != stage() && w.getScene() != null) {
                    var cells = all(w.getScene().getRoot(), new ArrayList<>()).stream()
                            .filter(n -> n instanceof javafx.scene.control.ListCell<?> c && c.getText() != null)
                            .map(n -> ((javafx.scene.control.ListCell<?>) n).getText()).toList();
                    if (cells.containsAll(List.of("Alpha", "https://example.com/b"))) {
                        return true;
                    }
                }
            }
            return false;
        });
        fx(() -> {
            recent().hide();
            return null;
        });
    }

    @Test
    void failedDownloadShowsTheError() throws Exception {
        download = (command, onLine) -> new VideoDownloader.Result(1, "", "ERROR: Video unavailable");
        show();
        enter(URL);
        await(() -> "yt-dlp failed: ERROR: Video unavailable".equals(statusText()));
        assertNull(fx(() -> find(root(), MediaView.class).getMediaPlayer()));
    }

    private long downloads() {
        return commands.stream().filter(c -> c.contains("-o")).count();
    }

    @Test
    void unplayableCachedFileIsDroppedAndFetchedOnceThenReported() throws Exception {
        Files.createDirectories(cache);
        var bad = cache.resolve("dQw4w9WgXcQ.mp4");
        Files.writeString(bad, "this is not a video");
        download = (command, onLine) -> {
            var out = command.get(command.indexOf("-o") + 1).replace("%(ext)s", "mp4");
            Files.createDirectories(Path.of(out).getParent());
            Files.writeString(Path.of(out), "still not a video");
            return new VideoDownloader.Result(0, "", "");
        };
        show();
        enter(URL);
        await(() -> statusText().startsWith("Playback failed") || statusText().startsWith("Cannot play: "));
        // exactly one re-download, and the fresh file is kept (no loop)
        assertEquals(1, downloads());
        assertEquals("still not a video", Files.readString(bad));
        Thread.sleep(500);
        assertEquals(1, downloads());
        // The failed player is dropped, whether JavaFX threw at once or reported the error after showing it.
        assertNull(fx(() -> find(root(), MediaView.class).getMediaPlayer()), () -> "status: " + statusText());
    }

    @Test
    void unplayableCachedFileIsReplacedByAGoodDownload() throws Exception {
        needsClip();
        Files.createDirectories(cache);
        var bad = cache.resolve("dQw4w9WgXcQ.mp4");
        Files.writeString(bad, "this is not a video");
        show();
        enter(URL);
        awaitPlaying();
        assertEquals(1, downloads());
        assertEquals(Files.size(clip), Files.size(bad));
    }

    @Test
    void secondLinkIgnoresTheStaleFirstDownload() throws Exception {
        needsClip();
        var gate = new CountDownLatch(1);
        download = (command, onLine) -> {
            if (command.contains(URL)) {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                onLine.accept("[download]  90.0% of 1MiB");
                return new VideoDownloader.Result(1, "", "ERROR: stale one failed");
            }
            return copyClip(command, onLine);
        };
        show();
        enter(URL);
        await(() -> "Loading…".equals(statusText()));
        enter(URL2);
        awaitPlaying();
        gate.countDown();
        Thread.sleep(300);
        fx(() -> null);
        assertEquals("", status());
        assertEquals(URL2, fx(() -> recent().getItems().get(0).url()));
    }

    @Test
    void closingSavesThePlaybackPosition() throws Exception {
        needsClip();
        show();
        enter(URL);
        awaitPlaying();
        await(() -> player().getCurrentTime().toSeconds() > 0.5);
        fx(() -> {
            BodyDoubleWindow.toggle(null, db);
            return null;
        });
        assertTrue(store.find(URL).positionMs() > 0, "position " + store.find(URL).positionMs());
    }

    /** Names for the glyphs on the play button. */
    private static final class VideoDownloaderPause {
        static final String PAUSE = "❚❚";
    }

    @Test
    void footerHintListsTheKeysAndEscCloses() throws Exception {
        show();
        var hint = fx(() -> root().lookupAll(".key-hint").stream().map(n -> ((Label) n).getText()).toList());
        assertEquals(1, hint.size());
        assertTrue(hint.get(0).contains("Esc close"), hint.get(0));
        assertTrue(hint.get(0).contains("Space play/pause"), hint.get(0));
        var primary = fx(() -> root().lookupAll(".button.primary").stream().map(n -> ((Button) n).getText()).toList());
        assertTrue(primary.isEmpty(), primary.toString());
    }
}
