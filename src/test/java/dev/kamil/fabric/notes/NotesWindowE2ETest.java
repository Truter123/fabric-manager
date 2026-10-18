package dev.kamil.fabric.notes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.EscapeClosesWindowsE2ETest;
import dev.kamil.fabric.FxHarness;
import dev.kamil.fabric.session.Database;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.Arguments;

/** Drives the real Notes window controls against a temporary H2 database; skipped without a display. */
class NotesWindowE2ETest {

    @TempDir
    Path dir;

    private Database db;
    private NoteStore store;

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
        db = Database.at(dir.resolve("e2e")).migrate();
        store = new NoteStore(db);
        NotesWindow.correctorCommand = List.of("tr", "a-z", "A-Z");
    }

    @AfterEach
    void close() throws Exception {
        NotesWindow.correctorCommand = EnglishCorrector.standard();
        fx(() -> {
            var stage = NotesWindow.openStage();
            if (stage != null) {
                stage.hide();
            }
            return null;
        });
    }

    /** The case of {@link EscapeClosesWindowsE2ETest}: an empty new note, so the close guard has nothing to ask. */
    static Stream<Arguments> escapeCase() {
        return Stream.of(EscapeClosesWindowsE2ETest.escapeCase("Notes", dir -> {
            var db = Database.at(dir.resolve("escape")).migrate();
            return FxHarness.fx(() -> {
                NotesWindow.open(null, db);
                return NotesWindow.openStage();
            });
        }, () -> FxHarness.fx(() -> {
            var stage = NotesWindow.openStage();
            if (stage != null) {
                stage.hide();
            }
            return null;
        })));
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

    private void openWindow() throws Exception {
        fx(() -> {
            NotesWindow.open(null, db);
            return null;
        });
        settle();
    }

    private static void settle() throws Exception {
        fx(() -> null);
        fx(() -> null);
    }

    private static Node root() {
        return NotesWindow.openStage().getScene().getRoot();
    }

    private Button button(String text) throws Exception {
        return fx(() -> {
            for (var n : root().lookupAll(".button")) {
                if (n instanceof Button b && text.equals(b.getText())) {
                    return b;
                }
            }
            throw new AssertionError("no button " + text);
        });
    }

    private ToggleButton chip(String text) throws Exception {
        return fx(() -> {
            for (var n : root().lookupAll(".notes-chip")) {
                if (n instanceof ToggleButton b && text.equals(b.getText())) {
                    return b;
                }
            }
            throw new AssertionError("no chip " + text);
        });
    }

    private List<String> chips() throws Exception {
        return fx(() -> root().lookupAll(".notes-chip").stream().map(n -> ((ToggleButton) n).getText()).toList());
    }

    private static TextField field(String prompt) {
        for (var n : root().lookupAll(".text-field")) {
            if (n instanceof TextField t && prompt.equals(t.getPromptText())) {
                return t;
            }
        }
        throw new AssertionError("no field " + prompt);
    }

    private static TextArea body() {
        return (TextArea) root().lookup(".note-body");
    }

    @SuppressWarnings("unchecked")
    private static ListView<Note> list() {
        return (ListView<Note>) root().lookup(".list-view");
    }

    private void click(String text) throws Exception {
        var b = button(text);
        fx(() -> {
            b.fire();
            return null;
        });
        settle();
    }

    private void typeBody(String text) throws Exception {
        fx(() -> {
            body().setText(text);
            return null;
        });
    }

    private void typeTitle(String text) throws Exception {
        fx(() -> {
            field("Title (optional)").setText(text);
            return null;
        });
    }

    private void key(KeyCode code) throws Exception {
        fx(() -> {
            var scene = NotesWindow.openStage().getScene();
            Node target = body();
            target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, true, false, false));
            return null;
        });
        settle();
    }

    /** Rendered titles of the non-empty cells, in order; call on the FX thread. */
    private static List<String> rowTitles() {
        var out = new ArrayList<String>();
        for (var n : list().lookupAll(".list-cell")) {
            if (n instanceof javafx.scene.control.ListCell<?> c && c.getGraphic() != null) {
                out.add(((javafx.scene.control.Label) c.getGraphic().lookup(".note-title")).getText());
            }
        }
        return out;
    }

    private List<Note> saved() {
        return store.recent(100);
    }

    // ---- tests

    @Test
    void typingAutosavesCreatesTheNoteAndShowsItInTheList() throws Exception {
        openWindow();
        typeTitle("Plan");
        typeBody("Ship it #work");
        await(() -> saved().size() == 1);
        var note = saved().get(0);
        assertEquals("Plan", note.title());
        assertEquals("Ship it #work", note.text());
        await(() -> rowTitles().equals(List.of("Plan")));
        assertTrue(chips().contains("#work"));
        assertEquals("Saved", fx(() -> ((javafx.scene.control.Label) root().lookup(".note-status")).getText()));
    }

    @Test
    void editingAnExistingNoteUpdatesIt() throws Exception {
        long id = store.create("T", "first", false);
        openWindow();
        fx(() -> {
            list().getSelectionModel().select(0);
            return null;
        });
        assertEquals("first", fx(() -> body().getText()));
        typeBody("second");
        await(() -> store.find(id).text().equals("second"));
        assertEquals(1, saved().size());
    }

    @Test
    void severalNotesRenderAndSurviveRepeatedRefresh() throws Exception {
        store.create("One", "a", false);
        store.create("Two", "b", false);
        store.create("Three", "c", false);
        openWindow();
        await(() -> rowTitles().size() == 3);
        typeBody("d");
        typeTitle("Four");
        await(() -> saved().size() == 4);
        await(() -> rowTitles().size() == 4 && rowTitles().contains("Four"));
    }

    @Test
    void selectingANoteLoadsItIntoTheEditor() throws Exception {
        store.create("A", "alpha", false);
        store.create("B", "beta", false);
        openWindow();
        fx(() -> {
            list().getSelectionModel().select(0);
            return null;
        });
        var first = fx(() -> body().getText());
        fx(() -> {
            list().getSelectionModel().select(1);
            return null;
        });
        var second = fx(() -> body().getText());
        assertEquals(java.util.Set.of("alpha", "beta"), java.util.Set.of(first, second));
        assertEquals(second.equals("alpha") ? "A" : "B", fx(() -> field("Title (optional)").getText()));
    }

    @Test
    void newButtonClearsTheEditorAndSavesPendingEdits() throws Exception {
        openWindow();
        typeBody("pending");
        click("New");
        assertEquals("", fx(() -> body().getText()));
        assertEquals(1, saved().size());
        assertEquals("pending", saved().get(0).text());
    }

    @Test
    void pinButtonTogglesPinAndPinnedChipFilters() throws Exception {
        store.create("Pin me", "x", false);
        store.create("Other", "y", false);
        openWindow();
        fx(() -> {
            list().getSelectionModel().select(0);
            return null;
        });
        var title = fx(() -> field("Title (optional)").getText());
        click("Pin");
        assertEquals(1, store.pinned().size());
        assertEquals(title, store.pinned().get(0).title());
        assertEquals("Unpin", button("Unpin").getText());
        click(chip("Pinned"));
        await(() -> rowTitles().equals(List.of(title)));
        click("Unpin");
        assertTrue(store.pinned().isEmpty());
        click(chip("All"));
        await(() -> rowTitles().size() == 2);
    }

    private void click(ToggleButton c) throws Exception {
        fx(() -> {
            c.fire();
            return null;
        });
        settle();
    }

    @Test
    void inboxChipShowsCapturedNotesOnly() throws Exception {
        store.capture("captured thought");
        store.create("Normal", "n", false);
        openWindow();
        click(chip("Inbox"));
        await(() -> rowTitles().size() == 1);
        click(chip("Inbox")); // deselect returns to All
        await(() -> rowTitles().size() == 2);
    }

    @Test
    void tagChipFiltersByTag() throws Exception {
        store.create("Tagged", "text #alpha", false);
        store.create("Plain", "no tag", false);
        openWindow();
        click(chip("#alpha"));
        await(() -> rowTitles().equals(List.of("Tagged")));
    }

    @Test
    void searchFieldFiltersTheList() throws Exception {
        store.create("Apples", "red fruit", false);
        store.create("Cars", "fast", false);
        openWindow();
        fx(() -> {
            field("Search notes").setText("fruit");
            return null;
        });
        await(() -> rowTitles().equals(List.of("Apples")));
        fx(() -> {
            field("Search notes").setText("");
            return null;
        });
        await(() -> rowTitles().size() == 2);
    }

    @Test
    void deleteThenUndoRestoresTheNote() throws Exception {
        long id = store.create("Gone", "bye", false);
        openWindow();
        fx(() -> {
            list().getSelectionModel().select(0);
            return null;
        });
        click("Delete");
        assertNull(store.find(id));
        assertTrue(fx(() -> root().lookup(".undo-bar").isVisible()));
        await(() -> rowTitles().isEmpty());
        click("Undo");
        assertFalse(fx(() -> root().lookup(".undo-bar").isVisible()));
        assertEquals(1, saved().size());
        await(() -> rowTitles().equals(List.of("Gone")));
        assertEquals("bye", fx(() -> body().getText()));
    }

    @Test
    void keyboardShortcutsNewPinDelete() throws Exception {
        long id = store.create("Keys", "k", false);
        openWindow();
        fx(() -> {
            list().getSelectionModel().select(0);
            return null;
        });
        key(KeyCode.P);
        assertTrue(store.find(id).pinned());
        key(KeyCode.N);
        assertEquals("", fx(() -> body().getText()));
        fx(() -> {
            list().getSelectionModel().select(0);
            return null;
        });
        fx(() -> {
            list().requestFocus();
            list().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DELETE, false, true, false, false));
            return null;
        });
        settle();
        assertNull(store.find(id));
    }

    @Test
    void ctrlDeleteInTheBodyKeepsTheNote() throws Exception {
        long id = store.create("Keep", "one two", false);
        openWindow();
        fx(() -> {
            list().getSelectionModel().select(0);
            body().requestFocus();
            return null;
        });
        key(KeyCode.DELETE);
        assertNotNull(store.find(id));
        assertFalse(fx(() -> root().lookup(".undo-bar").isVisible()));
        assertEquals(" two", fx(() -> body().getText()), "the text area deleted the word, not the note");
    }

    @Test
    void failedSaveShowsInRedAndClosingAsks() throws Exception {
        openWindow();
        typeBody("unsaved");
        try (var con = db.connect(); var st = con.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
        await(() -> "Saving failed".equals(((javafx.scene.control.Label) root().lookup(".note-status")).getText()));
        assertTrue(fx(() -> root().lookup(".note-status").getStyleClass().contains("note-status-error")));
        fx(() -> {
            Platform.runLater(() -> {
                var stage = NotesWindow.openStage();
                stage.fireEvent(new javafx.stage.WindowEvent(stage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
            });
            return null;
        });
        await(() -> dev.kamil.fabric.FxHarness.dialog() != null);
        dev.kamil.fabric.FxHarness.press("Cancel");
        assertTrue(fx(() -> NotesWindow.openStage().isShowing()));
    }

    @Test
    void fixEnglishShowsCorrectionAndReplaceAppliesIt() throws Exception {
        long id = store.create("T", "hello", false);
        openWindow();
        fx(() -> {
            list().getSelectionModel().select(0);
            return null;
        });
        click("Fix English");
        await(() -> root().lookup(".note-fix").isVisible());
        assertEquals("HELLO", fx(() -> ((TextArea) root().lookup(".note-fix .text-area")).getText()));
        click("Replace");
        assertEquals("HELLO", fx(() -> body().getText()));
        assertFalse(fx(() -> root().lookup(".note-fix").isVisible()));
        await(() -> store.find(id).text().equals("HELLO"));
    }

    @Test
    void fixEnglishDismissAndFailure() throws Exception {
        store.create("T", "hello", false);
        openWindow();
        fx(() -> {
            list().getSelectionModel().select(0);
            return null;
        });
        click("Fix English");
        await(() -> root().lookup(".note-fix").isVisible());
        click("Dismiss");
        assertFalse(fx(() -> root().lookup(".note-fix").isVisible()));
        assertEquals("hello", fx(() -> body().getText()));
        NotesWindow.correctorCommand = List.of("false");
        fx(() -> {
            NotesWindow.openStage().hide();
            return null;
        });
        openWindow();
        fx(() -> {
            list().getSelectionModel().select(0);
            return null;
        });
        click("Fix English");
        await(() -> ((javafx.scene.control.Label) root().lookup(".note-status")).getText()
                .startsWith("Correction failed"));
        var fix = button("Fix English");
        assertFalse(fx(fix::isDisabled));
    }

    @SuppressWarnings("unchecked")
    @Test
    void projectDropdownListsProjectsAndAssignsOne() throws Exception {
        try (var con = db.connect(); var st = con.createStatement()) {
            st.executeUpdate("INSERT INTO project (path, last_opened, last_tool) VALUES ('/tmp/demo-proj',"
                    + " CURRENT_TIMESTAMP, 'x')");
        }
        long id = store.create("P", "p", false);
        openWindow();
        fx(() -> {
            list().getSelectionModel().select(0);
            return null;
        });
        var combo = (javafx.scene.control.ComboBox<String>) fx(() -> root().lookup(".combo-box"));
        assertEquals(java.util.Arrays.asList(null, "/tmp/demo-proj"), fx(() -> new ArrayList<>(combo.getItems())));
        assertEquals("No project", fx(() -> combo.getButtonCell().getText()));
        fx(() -> {
            combo.getSelectionModel().select("/tmp/demo-proj");
            return null;
        });
        settle();
        assertEquals("/tmp/demo-proj", store.find(id).projectPath());
        assertEquals("demo-proj", fx(() -> combo.getButtonCell().getText()));
        await(() -> rowTitles().size() == 1);
    }

    @Test
    void openAgainReusesTheWindow() throws Exception {
        openWindow();
        Stage first = fx(NotesWindow::openStage);
        openWindow();
        assertTrue(first == fx(NotesWindow::openStage));
    }

    @Test
    void escapeInTheSearchFieldClearsItAndKeepsTheWindowOpen() throws Exception {
        openWindow();
        var search = fx(() -> field("Search notes"));
        fx(() -> {
            search.setText("milk");
            return null;
        });

        escapeIn(search);

        assertEquals("", fx(search::getText));
        assertTrue(fx(() -> NotesWindow.openStage().isShowing()));
    }

    @Test
    void escapeInTheTitleFieldKeepsTheTitleAndClosesTheWindow() throws Exception {
        openWindow();
        typeTitle("Groceries");
        var title = fx(() -> field("Title (optional)"));
        var stage = fx(NotesWindow::openStage);

        escapeIn(title);

        await(() -> !stage.isShowing());
        assertEquals("Groceries", fx(title::getText));
        assertEquals(List.of("Groceries"), saved().stream().map(Note::title).toList());
    }

    /** Focuses the field and sends it Esc, as a key press would arrive, then lets deferred work run. */
    private void escapeIn(TextField field) throws Exception {
        fx(() -> {
            field.requestFocus();
            return null;
        });
        await(() -> field.getScene().getFocusOwner() == field);
        fx(() -> {
            field.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            return null;
        });
        settle();
    }

    @Test
    void footerHintListsTheKeysAndEscCloses() throws Exception {
        openWindow();
        var hint = fx(() -> root().lookupAll(".key-hint").stream().map(n -> ((Label) n).getText()).toList());
        assertEquals(1, hint.size());
        assertTrue(hint.get(0).contains("Esc close"), hint.get(0));
        assertTrue(hint.get(0).contains("Ctrl+N new"), hint.get(0));
        var primary = fx(() -> root().lookupAll(".button.primary").stream().map(n -> ((Button) n).getText()).toList());
        assertEquals(1, primary.size(), primary.toString());
        assertTrue(primary.get(0).startsWith("New"), primary.get(0));
    }
}
