package dev.kamil.fabric.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.FxHarness;
import dev.kamil.fabric.git.BranchTree;
import dev.kamil.fabric.git.GitRepo;
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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the real History window against a throwaway repository with two authors, a branch and a worktree; skipped
 * without a display or git. The open-session callback is a stub, so no terminal or Claude is started.
 */
class HistoryWindowE2ETest {

    private static final List<Throwable> UNCAUGHT = Collections.synchronizedList(new ArrayList<>());

    @TempDir
    Path tmp;

    private Path repoDir;
    private Path worktreeDir;
    private final List<Path> opened = Collections.synchronizedList(new ArrayList<>());
    private Stage stage;

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

    @BeforeEach
    void openWindow() throws Exception {
        repoDir = Files.createDirectories(tmp.resolve("work"));
        git(repoDir, "alice", "init", "-b", "main");
        Files.writeString(repoDir.resolve("a.txt"), "one\n");
        git(repoDir, "alice", "add", "-A");
        git(repoDir, "alice", "commit", "-m", "first commit");
        Files.writeString(repoDir.resolve("a.txt"), "one\ntwo\n");
        git(repoDir, "bob", "commit", "-am", "second tweak");
        Files.writeString(repoDir.resolve("b.txt"), "bee\n");
        git(repoDir, "alice", "add", "-A");
        git(repoDir, "alice", "commit", "-m", "third addition");
        git(repoDir, "alice", "branch", "feature");
        worktreeDir = tmp.resolve("wt");
        git(repoDir, "alice", "worktree", "add", worktreeDir.toString(), "feature");
        var repo = GitRepo.of(repoDir).orElseThrow();
        fx(() -> {
            HistoryWindow.open(null, repo, "main", opened::add);
            return null;
        });
        await(() -> Window.getWindows().stream().anyMatch(w -> w instanceof Stage s && s.getTitle() != null
                && s.getTitle().startsWith("History")));
        stage = (Stage) fx(() -> Window.getWindows().stream()
                .filter(w -> w instanceof Stage s && s.getTitle() != null && s.getTitle().startsWith("History"))
                .findFirst().orElseThrow());
        await(() -> table().getItems().size() == 3 && branches().getRoot() != null);
    }

    @AfterEach
    void close() throws Exception {
        fx(() -> {
            stage.hide();
            return null;
        });
        assertTrue(UNCAUGHT.isEmpty(), "uncaught exceptions: " + UNCAUGHT);
    }

    @Test
    void listsCommitsNewestFirstWithRenderedCells() throws Exception {
        assertEquals(List.of("third addition", "second tweak", "first commit"),
                fx(() -> table().getItems().stream().map(GitRepo.Commit::subject).toList()));
        // every row paints text for its subject, author and hash (a throwing cell would leave rows blank)
        await(() -> cellTexts().containsAll(List.of("alice", "bob")));
        assertTrue(fx(() -> stage.getScene().getRoot().lookupAll(".graph-cell").stream()
                .anyMatch(n -> n instanceof javafx.scene.control.TableCell<?, ?> c && c.getGraphic() != null)));
    }

    @Test
    void filterNarrowsBySubjectAuthorAndHash() throws Exception {
        var hash = fx(() -> table().getItems().get(2).hash().substring(0, 7));
        fx(() -> {
            filter().setText("tweak");
            return null;
        });
        assertEquals(List.of("second tweak"), subjects());
        fx(() -> {
            filter().setText("ALICE");
            return null;
        });
        assertEquals(List.of("third addition", "first commit"), subjects());
        fx(() -> {
            filter().setText(hash);
            return null;
        });
        assertEquals(List.of("first commit"), subjects());
        fx(() -> {
            filter().setText("");
            return null;
        });
        assertEquals(3, subjects().size());
    }

    @Test
    void userFilterShowsOnlyThatAuthor() throws Exception {
        await(() -> userBox().getItems().size() >= 1);
        fx(() -> {
            userBox().setValue("bob");
            return null;
        });
        assertEquals(List.of("second tweak"), subjects());
        fx(() -> {
            userBox().setValue("All");
            return null;
        });
        assertEquals(3, subjects().size());
    }

    @Test
    void selectingACommitShowsItsDetailsAndFiles() throws Exception {
        fx(() -> {
            table().getSelectionModel().select(0);
            return null;
        });
        await(() -> labelTexts().contains("third addition") && labelTexts().contains("1 file changed"));
        fx(() -> {
            table().getSelectionModel().select(2);
            return null;
        });
        await(() -> labelTexts().contains("first commit") && labelTexts().contains("1 file changed"));
    }

    @Test
    void doubleClickingAWorktreeOpensASessionThere() throws Exception {
        await(() -> worktreeCell() != null);
        fx(() -> {
            var cell = worktreeCell();
            cell.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 1, 1, 1, 1, MouseButton.PRIMARY, 2, false, false,
                    false, false, true, false, false, true, false, false, null));
            return null;
        });
        assertEquals(1, opened.size());
        assertEquals(worktreeDir.toRealPath(), opened.get(0).toRealPath());
    }

    // ---- lookups (FX thread)

    @SuppressWarnings("unchecked")
    private TableView<GitRepo.Commit> table() {
        return (TableView<GitRepo.Commit>) stage.getScene().getRoot().lookup(".table-view");
    }

    @SuppressWarnings("unchecked")
    private TreeView<BranchTree.Node> branches() {
        return (TreeView<BranchTree.Node>) stage.getScene().getRoot().lookup(".tree-view");
    }

    private TextField filter() {
        return stage.getScene().getRoot().lookupAll(".text-field").stream().map(n -> (TextField) n)
                .filter(t -> t.getPromptText() != null && t.getPromptText().startsWith("Filter by subject"))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private ComboBox<String> userBox() {
        return (ComboBox<String>) stage.getScene().getRoot().lookup(".combo-box");
    }

    private TreeCell<?> worktreeCell() {
        return stage.getScene().getRoot().lookupAll(".tree-cell").stream().map(n -> (TreeCell<?>) n)
                .filter(c -> !c.isEmpty() && c.getItem() instanceof BranchTree.Node n && n.worktree() != null)
                .findFirst().orElse(null);
    }

    /** The filtered list the table is bound to. */
    private List<String> subjects() throws Exception {
        return fx(() -> table().getItems().stream().map(GitRepo.Commit::subject).toList());
    }

    private List<String> labelTexts() {
        return stage.getScene().getRoot().lookupAll(".label").stream()
                .map(n -> ((Label) n).getText()).filter(t -> t != null).toList();
    }

    private List<String> cellTexts() {
        return stage.getScene().getRoot().lookupAll(".table-cell").stream()
                .map(n -> ((javafx.scene.control.TableCell<?, ?>) n).getText()).filter(t -> t != null).toList();
    }

    // ---- harness

    private static void git(Path dir, String author, String... args) throws Exception {
        var command = new ArrayList<String>(List.of("git", "-C", dir.toString(), "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        var env = builder.environment();
        env.put("GIT_AUTHOR_NAME", author);
        env.put("GIT_AUTHOR_EMAIL", author + "@example.com");
        env.put("GIT_COMMITTER_NAME", author);
        env.put("GIT_COMMITTER_EMAIL", author + "@example.com");
        var process = builder.start();
        var out = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), "git " + String.join(" ", args) + ": " + out);
    }

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

    @Test
    void showsOneKeyHintListingEsc() throws Exception {
        var hints = fx(() -> stage.getScene().getRoot().lookupAll(".key-hint").stream().map(n -> ((Label) n).getText()).toList());
        assertEquals(1, hints.size(), hints.toString());
        assertTrue(hints.get(0).contains("Esc"), hints.get(0));
        assertTrue(hints.get(0).contains("F5"), hints.get(0));
    }
}
