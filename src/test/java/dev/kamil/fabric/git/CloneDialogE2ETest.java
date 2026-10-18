package dev.kamil.fabric.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.FxHarness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the real Clone dialog against local bare repositories only; skipped without a display or git. */
class CloneDialogE2ETest {

    private static final List<Throwable> UNCAUGHT = Collections.synchronizedList(new ArrayList<>());

    @TempDir
    Path tmp;

    private CloneDialog dialog;
    private Path parent;
    private Path remote;

    @BeforeAll
    static void startFx() {
        Assumptions.assumeTrue(FxHarness.displayAvailable(),
                "no display");
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // another test class started it
        }
        Platform.setImplicitExit(false);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> UNCAUGHT.add(e));
    }

    @AfterEach
    void close() throws Exception {
        if (dialog != null) {
            fx(() -> {
                dialog.close();
                return null;
            });
        }
        assertTrue(UNCAUGHT.isEmpty(), "uncaught exceptions: " + UNCAUGHT);
    }

    // ---- fixtures

    private static String git(Path dir, String... args) throws Exception {
        var command = new ArrayList<String>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var out = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), "git " + String.join(" ", args) + ": " + out);
        return out.strip();
    }

    /** A local bare repository standing in for the remote; never the network. */
    private void makeRemote() throws Exception {
        var src = Files.createDirectories(tmp.resolve("src"));
        git(src, "init", "-b", "main");
        git(src, "config", "user.name", "Test");
        git(src, "config", "user.email", "test@example.com");
        git(src, "config", "commit.gpgsign", "false");
        Files.writeString(src.resolve("hello.txt"), "hello\n");
        git(src, "add", "-A");
        git(src, "commit", "-m", "first");
        remote = tmp.resolve("remote.git");
        git(tmp, "clone", "--bare", src.toString(), remote.toString());
    }

    private void open() throws Exception {
        parent = Files.createDirectories(tmp.resolve("parent"));
        dialog = fx(() -> {
            var d = new CloneDialog(null, parent);
            d.show();
            return d;
        });
        // the clipboard may hold anything: start from a known state
        setUrl("");
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
        var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < end) {
            if (fx(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("condition not reached");
    }

    private TextField field(int index) {
        return (TextField) dialog.getDialogPane().lookupAll(".text-field").toArray()[index];
    }

    private void setUrl(String url) throws Exception {
        fx(() -> {
            field(0).setText(url);
            return null;
        });
    }

    private void setParent(String path) throws Exception {
        fx(() -> {
            field(1).setText(path);
            return null;
        });
    }

    private Button cloneButton() {
        return (Button) dialog.getDialogPane().lookupButton(dialog.getDialogPane().getButtonTypes().get(0));
    }

    private String targetText() {
        return dialog.getDialogPane().lookupAll(".project-path").stream().map(n -> ((Label) n).getText())
                .findFirst().orElseThrow();
    }

    private String statusText() {
        return dialog.getDialogPane().lookupAll(".clone-error").stream().map(n -> ((Label) n).getText())
                .findFirst().orElseThrow();
    }

    private void pressClone() throws Exception {
        fx(() -> {
            cloneButton().fire();
            return null;
        });
    }

    // ---- tests

    @Test
    void startsEmptyWithDefaultParentAndCloneDisabled() throws Exception {
        makeRemote();
        open();
        assertEquals(parent.toString(), fx(() -> field(1).getText()));
        assertEquals("Will clone into: …", fx(this::targetText));
        assertTrue(fx(() -> cloneButton().isDisabled()));
        assertTrue(fx(() -> dialog.getDialogPane().lookupAll(".button").stream()
                .anyMatch(n -> n instanceof Button b && "Choose…".equals(b.getText()) && !b.isDisabled())));
        assertEquals(List.of("Clone", "Cancel"), fx(() -> dialog.getDialogPane().getButtonTypes().stream()
                .map(ButtonType::getText).toList()));
    }

    @Test
    void typingAUrlShowsTheTargetAndEnablesClone() throws Exception {
        makeRemote();
        open();
        setUrl(remote.toString());
        assertEquals("Will clone into: " + parent.resolve("remote"), fx(this::targetText));
        assertFalse(fx(() -> cloneButton().isDisabled()));
        assertEquals("", fx(this::statusText));
    }

    @Test
    void invalidParentDisablesClone() throws Exception {
        makeRemote();
        open();
        setUrl(remote.toString());
        setParent(tmp.resolve("missing").toString());
        assertTrue(fx(() -> cloneButton().isDisabled()));
        assertEquals("Will clone into: " + tmp.resolve("missing"), fx(this::targetText));
        setParent(parent.toString());
        assertFalse(fx(() -> cloneButton().isDisabled()));
    }

    @Test
    void aDisabledCloneButtonSaysWhy() throws Exception {
        makeRemote();
        open();
        assertTrue(fx(() -> cloneButton().isDisabled()));
        assertEquals("Enter a repository URL", fx(this::statusText));
        setUrl(remote.toString());
        assertEquals("", fx(this::statusText));
        setParent(tmp.resolve("missing").toString());
        assertTrue(fx(() -> cloneButton().isDisabled()));
        assertEquals("Parent folder does not exist", fx(this::statusText));
        setParent(parent.toString());
        assertEquals("", fx(this::statusText));
        assertFalse(fx(() -> cloneButton().isDisabled()));
    }

    @Test
    void existingNonEmptyFolderIsRefused() throws Exception {
        makeRemote();
        open();
        Files.createDirectories(parent.resolve("remote"));
        Files.writeString(parent.resolve("remote").resolve("keep.txt"), "mine");
        setUrl(remote.toString());
        assertEquals("Folder already exists", fx(this::statusText));
        assertTrue(fx(() -> cloneButton().isDisabled()));
    }

    @Test
    void existingEmptyFolderIsAccepted() throws Exception {
        makeRemote();
        open();
        Files.createDirectories(parent.resolve("remote"));
        setUrl(remote.toString());
        assertEquals("", fx(this::statusText));
        assertFalse(fx(() -> cloneButton().isDisabled()));
    }

    @Test
    void urlThatLooksLikeAnOptionIsRejected() throws Exception {
        open();
        setUrl("--upload-pack=touch /tmp/pwned");
        pressClone();
        assertEquals("URL must not start with \"-\"", fx(this::statusText));
        assertFalse(fx(() -> field(0).isDisabled()));
        assertFalse(Files.exists(Path.of("/tmp/pwned")));
    }

    @Test
    void clonesALocalBareRepositoryAndReturnsTheFolder() throws Exception {
        makeRemote();
        open();
        setUrl(remote.toString());
        pressClone();
        await(() -> !dialog.isShowing());
        var result = fx(dialog::getResult);
        assertEquals(parent.resolve("remote"), result);
        assertEquals("hello\n", Files.readString(result.resolve("hello.txt")));
        assertEquals("first", git(result, "log", "-1", "--format=%s"));
    }

    @Test
    void failedCloneShowsLogAndRestoresTheForm() throws Exception {
        open();
        var missing = tmp.resolve("does-not-exist.git");
        setUrl(missing.toString());
        pressClone();
        await(() -> statusText().startsWith("Clone failed (exit"));
        assertTrue(fx(() -> dialog.getDialogPane().lookupAll(".clone-log").stream()
                .anyMatch(n -> n instanceof TextArea a && a.isVisible() && a.getText().contains("does-not-exist"))));
        assertFalse(fx(() -> field(0).isDisabled()));
        assertFalse(fx(() -> field(1).isDisabled()));
        assertFalse(fx(() -> cloneButton().isDisabled()));
        assertFalse(Files.exists(parent.resolve("does-not-exist")), "no leftover folder");
        assertNull(fx(dialog::getResult));
        assertTrue(dialog.isShowing());
    }

    @Test
    void cancelClosesTheDialogWithoutResult() throws Exception {
        open();
        setUrl("https://example.invalid/some/repo.git");
        fx(() -> {
            ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).fire();
            return null;
        });
        await(() -> !dialog.isShowing());
        assertNull(fx(dialog::getResult));
        assertFalse(Files.exists(parent.resolve("repo")));
    }
}
