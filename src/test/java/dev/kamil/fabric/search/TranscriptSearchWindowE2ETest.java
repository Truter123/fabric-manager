package dev.kamil.fabric.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.FxHarness;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
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

/** Drives the real transcript-search popup over transcripts in a temporary claude home. */
class TranscriptSearchWindowE2ETest {

    @TempDir
    Path home;

    private Stage owner;
    private TranscriptSearchWindow popup;
    private Stage popupStage;
    private final List<TranscriptSearch.Hit> chosen = new CopyOnWriteArrayList<>();

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
    }

    @BeforeEach
    void setUp() throws Exception {
        transcript("proj-a", "sess-old", "/work/alpha", "2026-01-01T10:00:00Z", 1000,
                "user", "How do I configure the Gradle build?");
        transcript("proj-b", "sess-new", "/work/beta", "2026-02-01T10:00:00Z", 2000,
                "assistant", "Run the gradle wrapper first, then test.");
        transcript("proj-c", "sess-other", "/work/gamma", "2026-03-01T10:00:00Z", 3000,
                "user", "Nothing relevant here");
        fx(() -> {
            owner = new Stage();
            owner.setScene(new javafx.scene.Scene(new javafx.scene.layout.StackPane(), 800, 600));
            owner.show();
            popup = new TranscriptSearchWindow(owner, List.of(home), chosen::add);
            popup.show();
            for (var w : Window.getWindows()) {
                if (w instanceof Stage s && s != owner && s.getOwner() == owner) {
                    popupStage = s;
                }
            }
            return null;
        });
    }

    @AfterEach
    void close() throws Exception {
        fx(() -> {
            popupStage.hide();
            owner.hide();
            return null;
        });
    }

    private void transcript(String projectDir, String id, String cwd, String stamp, long modified, String role,
            String text) throws IOException {
        var dir = Files.createDirectories(home.resolve("projects").resolve(projectDir));
        var file = dir.resolve(id + ".jsonl");
        Files.writeString(file, "{\"type\":\"summary\"}\n{\"type\":\"" + role + "\",\"cwd\":\"" + cwd
                + "\",\"timestamp\":\"" + stamp + "\",\"message\":{\"role\":\"" + role + "\",\"content\":\""
                + text + "\"}}\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(modified));
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

    private Node root() {
        return popupStage.getScene().getRoot();
    }

    @SuppressWarnings("unchecked")
    private ListView<TranscriptSearch.Hit> list() {
        return (ListView<TranscriptSearch.Hit>) root().lookup(".list-view");
    }

    private void type(String text) throws Exception {
        fx(() -> {
            ((TextField) root().lookup(".text-field")).setText(text);
            return null;
        });
    }

    private void key(KeyCode code) throws Exception {
        fx(() -> {
            root().lookup(".text-field").fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false,
                    false, false));
            return null;
        });
    }

    private List<String> sessions() {
        return list().getItems().stream().map(TranscriptSearch.Hit::sessionId).toList();
    }

    /** Heads of the rendered cells (project, date, role); call on the FX thread. */
    private List<String> heads() {
        var out = new ArrayList<String>();
        for (var n : list().lookupAll(".list-cell")) {
            if (n instanceof ListCell<?> c && c.getGraphic() != null) {
                out.add(((Label) c.getGraphic().lookup(".snippet-name")).getText());
            }
        }
        return out;
    }

    // ---- tests

    @Test
    void typingFindsMatchingSessionsNewestFirstAndSelectsTheFirst() throws Exception {
        type("gradle");
        await(() -> sessions().equals(List.of("sess-new", "sess-old")));
        assertEquals(0, fx(() -> list().getSelectionModel().getSelectedIndex()));
        await(() -> heads().size() == 2);
        var heads = fx(this::heads);
        assertTrue(heads.get(0).startsWith("beta   2026-02-01"), heads.get(0));
        assertTrue(heads.get(0).endsWith("assistant"), heads.get(0));
        assertTrue(heads.get(1).startsWith("alpha   2026-01-01"), heads.get(1));
        assertTrue(heads.get(1).endsWith("user"), heads.get(1));
        var snippets = fx(() -> list().lookupAll(".snippet-preview").stream().map(n -> ((Label) n).getText())
                .toList());
        assertTrue(snippets.stream().anyMatch(s -> s.contains("gradle wrapper")), snippets.toString());
    }

    @Test
    void searchIsCaseInsensitiveAndRefinesAndReplacesResults() throws Exception {
        type("GRADLE");
        await(() -> sessions().size() == 2);
        type("wrapper");
        await(() -> sessions().equals(List.of("sess-new")));
        await(() -> heads().size() == 1);
        type("relevant");
        await(() -> sessions().equals(List.of("sess-other")));
    }

    @Test
    void noMatchAndBlankQueryShowNothing() throws Exception {
        type("gradle");
        await(() -> sessions().size() == 2);
        type("zzzz-not-there");
        await(() -> sessions().isEmpty());
        await(() -> "No results for \"zzzz-not-there\"".equals(placeholder()));
        type("gradle");
        await(() -> sessions().size() == 2);
        type("   ");
        await(() -> sessions().isEmpty());
        await(() -> "Type to search all sessions.".equals(placeholder()));
    }

    @Test
    void thePlaceholderSaysSearchingWhileASearchRuns() throws Exception {
        assertEquals("Type to search all sessions.", fx(this::placeholder));
        fx(() -> {
            // a search that has started and not yet answered: the generation guard drops a stale answer, so call
            // the real path (Enter) and read the placeholder in the same FX turn, before the background result lands
            ((TextField) root().lookup(".text-field")).setText("gradle");
            root().lookup(".text-field").fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false,
                    false, false, false));
            assertEquals("Searching…", placeholder());
            return null;
        });
        await(() -> sessions().size() == 2);
        assertEquals("Type to search all sessions.", fx(this::placeholder));
    }

    private String placeholder() {
        return ((Label) list().getPlaceholder()).getText();
    }

    @Test
    void enterChoosesTheSelectedHitAndClosesThePopup() throws Exception {
        type("gradle");
        await(() -> sessions().size() == 2);
        Thread.sleep(400); // let the debounce settle so Enter means "open"
        key(KeyCode.ENTER);
        await(() -> !chosen.isEmpty());
        assertEquals(1, chosen.size());
        assertEquals("sess-new", chosen.get(0).sessionId());
        assertEquals(Path.of("/work/beta"), chosen.get(0).project());
        await(() -> !popupStage.isShowing());
    }

    @Test
    void arrowKeysMoveTheSelectionWithinBounds() throws Exception {
        type("gradle");
        await(() -> sessions().size() == 2);
        key(KeyCode.DOWN);
        assertEquals(1, fx(() -> list().getSelectionModel().getSelectedIndex()));
        key(KeyCode.DOWN);
        assertEquals(1, fx(() -> list().getSelectionModel().getSelectedIndex()));
        key(KeyCode.UP);
        key(KeyCode.UP);
        assertEquals(0, fx(() -> list().getSelectionModel().getSelectedIndex()));
        key(KeyCode.DOWN);
        Thread.sleep(400);
        key(KeyCode.ENTER);
        await(() -> !chosen.isEmpty());
        assertEquals("sess-old", chosen.get(0).sessionId());
    }

    @Test
    void enterRightAfterTypingSearchesImmediately() throws Exception {
        type("relevant");
        key(KeyCode.ENTER);
        await(() -> sessions().equals(List.of("sess-other")));
        assertTrue(chosen.isEmpty());
    }

    @Test
    void doubleClickChoosesTheHit() throws Exception {
        type("gradle");
        await(() -> sessions().size() == 2);
        fx(() -> {
            list().getSelectionModel().select(1);
            list().fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 1, 1, 1, 1, MouseButton.PRIMARY, 2, false,
                    false, false, false, true, false, false, true, false, false, null));
            return null;
        });
        await(() -> !chosen.isEmpty());
        assertEquals("sess-old", chosen.get(0).sessionId());
    }

    @Test
    void escapeClosesWithoutChoosing() throws Exception {
        type("gradle");
        await(() -> sessions().size() == 2);
        key(KeyCode.ESCAPE);
        await(() -> !popupStage.isShowing());
        assertFalse(!chosen.isEmpty());
    }
}
