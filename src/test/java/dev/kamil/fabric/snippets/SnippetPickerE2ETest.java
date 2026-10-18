package dev.kamil.fabric.snippets;

import static dev.kamil.fabric.FxHarness.await;
import static dev.kamil.fabric.FxHarness.fx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.FxHarness;
import dev.kamil.fabric.session.Database;
import java.nio.file.Path;
import javafx.scene.Parent;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the real snippet picker against a temporary H2 database; skipped without a display. */
class SnippetPickerE2ETest {

    @TempDir
    Path dir;

    private SnippetStore store;
    private Stage owner;

    @BeforeAll
    static void startFx() {
        FxHarness.start();
    }

    @BeforeEach
    void setUp() throws Exception {
        store = new SnippetStore(Database.at(dir.resolve("e2e")).migrate());
        store.add("greet", "echo hi", null);
        fx(() -> {
            owner = new Stage();
            owner.show();
            new SnippetPicker(owner, store, text -> { }, () -> null).show();
            return null;
        });
        await(() -> root() != null);
    }

    @AfterEach
    void tearDown() throws Exception {
        fx(() -> {
            if (root() != null) {
                root().getScene().getWindow().hide();
            }
            owner.hide();
            return null;
        });
    }

    /** The picker's scene root, or null; call on the FX thread. */
    private static Parent root() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w.getScene() != null && w.getScene().getRoot().getStyleClass().contains("snippet-picker")) {
                return w.getScene().getRoot();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static ListView<Snippet> list() {
        return (ListView<Snippet>) root().lookup(".list-view");
    }

    private boolean stored(String name) {
        return store.list(null).stream().anyMatch(s -> s.name().equals(name));
    }

    /** Opens the editor with Ctrl+N; call on the FX thread. */
    private static void newSnippet() {
        root().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.N, false, true, false, false));
    }

    private static boolean visibleLabel(String style, String text) {
        return root().lookupAll(style).stream()
                .anyMatch(n -> n instanceof Label l && n.isVisible() && text.equals(l.getText()));
    }

    private static TextField nameField() {
        return root().lookupAll(".text-field").stream()
                .map(n -> (TextField) n).filter(f -> "Name".equals(f.getPromptText())).findFirst().orElseThrow();
    }

    @Test
    void savingABlankSnippetShowsWhyAndKeepsTheEditorOpen() throws Exception {
        fx(() -> {
            newSnippet();
            return null;
        });
        assertTrue(fx(() -> visibleLabel(".snippet-name", "New snippet")));
        assertFalse(fx(() -> visibleLabel(".inline-error", "Name and text are required")));
        var save = fx(() -> root().lookupAll(".button").stream().map(n -> (Button) n)
                .filter(b -> "Save".equals(b.getText())).findFirst().orElseThrow());
        assertTrue(fx(() -> save.getStyleClass().contains("primary")));
        fx(() -> {
            save.fire();
            return null;
        });
        assertTrue(fx(() -> visibleLabel(".inline-error", "Name and text are required")));
        assertTrue(fx(() -> nameField().getParent().isVisible()), "the editor stays open");
        fx(() -> {
            nameField().setText("x");
            return null;
        });
        assertFalse(fx(() -> visibleLabel(".inline-error", "Name and text are required")), "typing clears the error");
    }

    @Test
    void enterInTheNameFieldSavesAndEditingIsTitledEdit() throws Exception {
        fx(() -> {
            newSnippet();
            nameField().setText("fresh");
            root().lookupAll(".text-area").stream().map(n -> (TextArea) n).findFirst().orElseThrow()
                    .setText("echo fresh");
            nameField().fireEvent(new ActionEvent());
            return null;
        });
        assertTrue(stored("fresh"));
        fx(() -> {
            list().getSelectionModel().select(0);
            root().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.E, false, true, false, false));
            return null;
        });
        assertTrue(fx(() -> visibleLabel(".snippet-name", "Edit snippet")));
    }

    @Test
    void deleteThenUndoPutsTheSnippetBack() throws Exception {
        fx(() -> {
            var greet = list().getItems().stream().filter(s -> s.name().equals("greet")).findFirst().orElseThrow();
            list().getSelectionModel().select(greet);
            root().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DELETE, false, true, false, false));
            return null;
        });
        assertFalse(stored("greet"));
        assertTrue(fx(() -> root().lookup(".undo-bar").isVisible()));
        fx(() -> {
            ((Button) root().lookup(".undo-bar .button")).fire();
            return null;
        });
        assertTrue(stored("greet"));
        assertEquals("echo hi", store.list(null).stream().filter(s -> s.name().equals("greet")).findFirst().orElseThrow().text());
        assertFalse(fx(() -> root().lookup(".undo-bar").isVisible()));
        await(() -> list().getItems().stream().anyMatch(s -> s.name().equals("greet")));
    }
}
