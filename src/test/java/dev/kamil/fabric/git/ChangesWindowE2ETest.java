package dev.kamil.fabric.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.FxHarness;
import java.io.IOException;
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
import javafx.event.Event;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the real Changes window controls against a throwaway repository; skipped without a display or git. */
class ChangesWindowE2ETest {

    private static final List<Throwable> UNCAUGHT = Collections.synchronizedList(new ArrayList<>());

    @TempDir
    Path tmp;

    private Path repo;
    private ChangesWindow window;

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
        if (window != null) {
            fx(() -> {
                window.stage().hide();
                return null;
            });
        }
        assertTrue(UNCAUGHT.isEmpty(), "uncaught exceptions: " + UNCAUGHT);
    }

    // ---- git fixture

    private static String git(Path dir, String... args) throws Exception {
        var command = new ArrayList<String>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var out = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), "git " + String.join(" ", args) + ": " + out);
        return out.strip();
    }

    /** Repo with two committed files, a.txt modified, b.txt untouched and c.txt untracked. */
    private void openDirtyRepo() throws Exception {
        repo = Files.createDirectories(tmp.resolve("work"));
        git(repo, "init", "-b", "main");
        git(repo, "config", "user.name", "Test");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("a.txt"), "one\ntwo\n");
        Files.writeString(repo.resolve("b.txt"), "bee\n");
        git(repo, "add", "-A");
        git(repo, "commit", "-m", "init");
        Files.writeString(repo.resolve("a.txt"), "one\nTWO\nthree\n");
        Files.writeString(repo.resolve("c.txt"), "new file\n");
        open(repo);
        await(() -> rows().size() == 2);
    }

    private void open(Path dir) throws Exception {
        window = fx(() -> {
            var w = new ChangesWindow(null, dir);
            w.show();
            return w;
        });
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
        var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            if (fx(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("condition not reached");
    }

    private static void awaitThreadsDone() throws Exception {
        await(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().equals("changes-git")));
        fx(() -> null);
    }

    @SuppressWarnings("unchecked")
    private ListView<GitRepo.Change> list() {
        return (ListView<GitRepo.Change>) window.stage().getScene().getRoot().lookupAll(".list-view").iterator().next();
    }

    @SuppressWarnings("unchecked")
    private ListView<DiffView.Row> diff() {
        return (ListView<DiffView.Row>) window.stage().getScene().getRoot().lookup(".diff-view");
    }

    /** "U c.txt" style rows of the changes list, read from the rendered cells. Call on the FX thread. */
    private List<String> rows() {
        var out = new ArrayList<String>();
        for (var node : list().lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> cell && !cell.isEmpty() && cell.getGraphic() instanceof HBox box) {
                out.add(((Label) box.getChildren().get(0)).getText() + " " + ((Label) box.getChildren().get(1)).getText());
            }
        }
        out.sort(null);
        return out;
    }

    private List<String> diffTexts() throws Exception {
        return fx(() -> diff().getItems().stream().map(DiffView.Row::text).toList());
    }

    /** Call on the FX thread. */
    private Button button(String text) {
        for (var n : window.stage().getScene().getRoot().lookupAll(".button")) {
            if (n instanceof Button b && text.equals(b.getText())) {
                return b;
            }
        }
        throw new AssertionError("no button " + text);
    }

    /** Fires the button later: a confirm dialog's nested loop would otherwise block the caller. */
    private void click(String text) throws Exception {
        Platform.runLater(() -> button(text).fire());
        fx(() -> null);
    }

    private void select(String path) throws Exception {
        fx(() -> {
            var item = list().getItems().stream().filter(c -> c.path().equals(path)).findFirst().orElseThrow();
            list().getSelectionModel().select(item);
            return null;
        });
    }

    private static DialogPane dialog() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w.getScene() != null && w.getScene().getRoot() instanceof DialogPane p) {
                return p;
            }
        }
        return null;
    }

    /** Clicks the dialog button with this text (a modal dialog blocks the FX thread loop, so it runs later). */
    private void answer(String buttonText) throws Exception {
        await(() -> dialog() != null);
        fx(() -> {
            var pane = dialog();
            var type = pane.getButtonTypes().stream().filter(t -> t.getText().equals(buttonText)).findFirst()
                    .orElseThrow();
            ((Button) pane.lookupButton(type)).fire();
            return null;
        });
        await(() -> dialog() == null);
    }

    /** Call on the FX thread. */
    private TextField message() {
        return (TextField) window.stage().getScene().getRoot().lookup(".text-field");
    }

    // ---- tests

    @Test
    void listsTrackedAndUntrackedChanges() throws Exception {
        openDirtyRepo();
        assertEquals(List.of("M a.txt", "U c.txt"), fx(this::rows));
        assertEquals("Changes — work", fx(() -> window.stage().getTitle()));
    }

    @Test
    void firstChangeIsSelectedAndItsDiffShown() throws Exception {
        openDirtyRepo();
        await(() -> !diff().getItems().isEmpty());
        assertEquals("a.txt", fx(() -> list().getSelectionModel().getSelectedItem().path()));
        var texts = diffTexts();
        assertTrue(texts.stream().anyMatch(t -> t.contains("TWO")), texts.toString());
        assertTrue(texts.stream().anyMatch(t -> t.contains("three")), texts.toString());
    }

    @Test
    void selectingAnotherFileSwitchesTheDiffIncludingUntracked() throws Exception {
        openDirtyRepo();
        select("c.txt");
        await(() -> diff().getItems().stream().anyMatch(r -> r.text().contains("new file")));
        var label = fx(() -> window.stage().getScene().getRoot().lookupAll(".label").stream()
                .filter(n -> n instanceof Label l && "c.txt".equals(l.getText()) && l.getStyle().contains("bold"))
                .count());
        assertEquals(1L, label);
        select("a.txt");
        await(() -> diff().getItems().stream().anyMatch(r -> r.text().contains("TWO")));
        assertFalse(diffTexts().stream().anyMatch(t -> t.contains("new file")));
    }

    @Test
    void commitNeedsAMessageRefreshIsEnabledAndDiscardNeedsSelection() throws Exception {
        openDirtyRepo();
        assertTrue(fx(() -> button("Commit all").isDisabled()), "blank message");
        assertFalse(fx(() -> button("Refresh").isDisabled()));
        assertFalse(fx(() -> button("Discard file").isDisabled()));
        fx(() -> {
            list().getSelectionModel().clearSelection();
            return null;
        });
        assertTrue(fx(() -> button("Discard file").isDisabled()));
    }

    @Test
    void refreshPicksUpNewFiles() throws Exception {
        openDirtyRepo();
        Files.writeString(repo.resolve("d.txt"), "later\n");
        click("Refresh");
        await(() -> rows().size() == 3);
        assertEquals(List.of("M a.txt", "U c.txt", "U d.txt"), fx(this::rows));
        // selection survives the reload
        assertEquals("a.txt", fx(() -> list().getSelectionModel().getSelectedItem().path()));
    }

    @Test
    void repeatedReloadsReuseCellsWithoutErrors() throws Exception {
        openDirtyRepo();
        for (var i = 0; i < 4; i++) {
            Files.writeString(repo.resolve("extra" + i + ".txt"), "x\n");
            click("Refresh");
            var expected = 3 + i;
            await(() -> rows().size() == expected);
        }
        for (var i = 0; i < 4; i++) {
            Files.delete(repo.resolve("extra" + i + ".txt"));
        }
        click("Refresh");
        await(() -> rows().size() == 2);
        assertTrue(UNCAUGHT.isEmpty(), UNCAUGHT.toString());
    }

    @Test
    void commitAllCommitsEverythingAndClearsTheList() throws Exception {
        openDirtyRepo();
        fx(() -> {
            message().setText("  my change  ");
            return null;
        });
        click("Commit all");
        await(() -> rows().isEmpty());
        assertEquals("my change", git(repo, "log", "-1", "--format=%s"));
        assertEquals("", git(repo, "status", "--porcelain"));
        assertEquals("", fx(() -> message().getText()));
        assertTrue(diffTexts().isEmpty());
        assertEquals("", fx(() -> window.stage().getScene().getRoot().lookupAll(".label").stream()
                .filter(n -> n instanceof Label l && l.getStyle().contains("bold")).map(n -> ((Label) n).getText())
                .findFirst().orElse("")));
    }

    @Test
    void commitWithBlankMessageDoesNothingAndKeepsChanges() throws Exception {
        openDirtyRepo();
        fx(() -> {
            message().setText("   ");
            return null;
        });
        assertTrue(fx(() -> button("Commit all").isDisabled()));
        click("Commit all");
        pressEnterInMessage();
        awaitThreadsDone();
        assertNull(fx(ChangesWindowE2ETest::dialog));
        assertEquals(2, fx(() -> list().getItems().size()));
        assertEquals("init", git(repo, "log", "-1", "--format=%s"));
    }

    @Test
    void commitIsPrimaryAndEnabledOnlyWhileTheMessageHasText() throws Exception {
        openDirtyRepo();
        assertTrue(fx(() -> button("Commit all").getStyleClass().contains("primary")));
        assertFalse(fx(() -> button("Discard file").getStyleClass().contains("primary")));
        assertTrue(fx(() -> button("Commit all").isDisabled()));
        fx(() -> {
            message().setText("x");
            return null;
        });
        assertFalse(fx(() -> button("Commit all").isDisabled()));
        fx(() -> {
            message().clear();
            return null;
        });
        assertTrue(fx(() -> button("Commit all").isDisabled()));
    }

    @Test
    void enterInTheMessageFieldCommits() throws Exception {
        openDirtyRepo();
        fx(() -> {
            message().setText("via enter");
            return null;
        });
        pressEnterInMessage();
        await(() -> rows().isEmpty());
        assertEquals("via enter", git(repo, "log", "-1", "--format=%s"));
        assertEquals("", git(repo, "status", "--porcelain"));
        assertEquals("", fx(() -> message().getText()));
    }

    @Test
    void discardTrackedFileRestoresItAfterConfirm() throws Exception {
        openDirtyRepo();
        select("a.txt");
        click("Discard file");
        await(() -> dialog() != null);
        assertEquals("Discard a.txt?", fx(() -> dialog().getHeaderText()));
        answer("Discard");
        await(() -> rows().size() == 1);
        assertEquals("one\ntwo\n", Files.readString(repo.resolve("a.txt")));
        assertEquals(List.of("U c.txt"), fx(this::rows));
    }

    @Test
    void discardUntrackedFileDeletesIt() throws Exception {
        openDirtyRepo();
        select("c.txt");
        click("Discard file");
        answer("Discard");
        await(() -> rows().size() == 1);
        assertFalse(Files.exists(repo.resolve("c.txt")));
        assertEquals(List.of("M a.txt"), fx(this::rows));
    }

    @Test
    void cancellingTheDiscardConfirmKeepsTheFile() throws Exception {
        openDirtyRepo();
        select("a.txt");
        click("Discard file");
        answer("Cancel");
        awaitThreadsDone();
        assertEquals("one\nTWO\nthree\n", Files.readString(repo.resolve("a.txt")));
        assertEquals(2, fx(() -> list().getItems().size()));
    }

    @Test
    void cleanRepositoryShowsPlaceholders() throws Exception {
        repo = Files.createDirectories(tmp.resolve("clean"));
        git(repo, "init", "-b", "main");
        open(repo);
        awaitThreadsDone();
        assertEquals(0, fx(() -> list().getItems().size()));
        assertEquals("No changes", fx(() -> ((Label) list().getPlaceholder()).getText()));
        assertEquals("No diff", fx(() -> ((Label) diff().getPlaceholder()).getText()));
    }

    @Test
    void notARepositoryShowsMessage() throws Exception {
        var plain = Files.createDirectories(tmp.resolve("plain"));
        open(plain);
        await(() -> window.stage().getScene().getRoot().lookupAll(".label").stream()
                .anyMatch(n -> n instanceof Label l && "Not a git repository".equals(l.getText())));
    }

    @Test
    void escapeClosesTheWindow() throws Exception {
        openDirtyRepo();
        fx(() -> {
            Event.fireEvent(window.stage().getScene().getRoot(),
                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            return null;
        });
        await(() -> !window.stage().isShowing());
        assertNotNull(window);
    }

    @Test
    void openingTheSameDirectoryTwiceReusesOneWindow() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("same"));
        git(dir, "init", "-b", "main");
        fx(() -> {
            ChangesWindow.open(null, dir);
            return null;
        });
        var first = fx(() -> ChangesWindow.openStage(dir));
        assertNotNull(first);
        awaitThreadsDone();

        fx(() -> {
            ChangesWindow.open(null, dir.resolve("..").resolve("same"));
            return null;
        });

        assertSame(first, fx(() -> ChangesWindow.openStage(dir)));
        assertEquals(1, fx(() -> Window.getWindows().stream()
                .filter(w -> w.isShowing() && w instanceof javafx.stage.Stage stage
                        && stage.getTitle().startsWith("Changes") && stage.getTitle().endsWith("same"))
                .count()).intValue());

        fx(() -> {
            first.hide();
            return null;
        });
        assertNull(fx(() -> ChangesWindow.openStage(dir)));
    }

    private void pressEnterInMessage() throws Exception {
        fx(() -> {
            Event.fireEvent(message(),
                    new KeyEvent(KeyEvent.KEY_PRESSED, "\r", "", KeyCode.ENTER, false, false, false, false));
            return null;
        });
    }

    @Test
    void showsOneKeyHintListingEsc() throws Exception {
        openDirtyRepo();
        var hints = fx(() -> window.stage().getScene().getRoot().lookupAll(".key-hint").stream().map(n -> ((Label) n).getText()).toList());
        assertEquals(1, hints.size(), hints.toString());
        assertEquals("Enter (in the message) commit   Esc close", hints.get(0));
    }
}
