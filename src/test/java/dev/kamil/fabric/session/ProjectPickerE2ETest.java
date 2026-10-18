package dev.kamil.fabric.session;

import static dev.kamil.fabric.FxHarness.await;
import static dev.kamil.fabric.FxHarness.fx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.FxHarness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the real project picker's session list against a temporary H2 database; skipped without a display. */
class ProjectPickerE2ETest {

    private static final Account PERSONAL = Account.personal(Path.of(System.getProperty("user.home")));

    @TempDir
    Path dir;

    private SessionStore store;
    private ProjectPicker picker;

    @BeforeAll
    static void startFx() {
        FxHarness.start();
    }

    @BeforeEach
    void setUp() throws Exception {
        var db = Database.at(dir.resolve("e2e")).migrate();
        store = new SessionStore(db);
        store.started("s1", Files.createDirectory(dir.resolve("proj")), Tool.SHELL, PERSONAL, "my session");
        var recent = new RecentProjects(db, dir.resolve("legacy.txt")).load();
        fx(() -> {
            picker = new ProjectPicker(null, recent, store, Set.of());
            picker.show();
            return null;
        });
        await(() -> sessions().getItems().size() == 1);
    }

    @AfterEach
    void tearDown() throws Exception {
        fx(() -> {
            picker.close();
            return null;
        });
    }

    /** The last list of the dialog (the combo boxes hold lists too); call on the FX thread. */
    @SuppressWarnings("unchecked")
    private ListView<SavedSession> sessions() {
        var lists = new ArrayList<>(picker.getDialogPane().lookupAll(".list-view"));
        return (ListView<SavedSession>) lists.get(lists.size() - 1);
    }

    @Test
    void deleteThenUndoPutsTheSessionBack() throws Exception {
        fx(() -> {
            sessions().getSelectionModel().select(0);
            sessions().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DELETE, false, false, false, false));
            return null;
        });
        assertTrue(store.recent(10).isEmpty());
        assertTrue(fx(() -> picker.getDialogPane().lookup(".undo-bar").isVisible()));
        await(() -> sessions().getItems().isEmpty());
        fx(() -> {
            ((Button) picker.getDialogPane().lookup(".undo-bar .button")).fire();
            return null;
        });
        assertEquals("my session", store.recent(10).get(0).title());
        assertFalse(fx(() -> picker.getDialogPane().lookup(".undo-bar").isVisible()));
        await(() -> sessions().getItems().size() == 1);
    }

    @Test
    void showsOneKeyHintListingEsc() throws Exception {
        var hints = fx(() -> picker.getDialogPane().lookupAll(".key-hint").stream().map(n -> ((Label) n).getText()).toList());
        assertEquals(1, hints.size(), hints.toString());
        assertTrue(hints.get(0).contains("Esc"), hints.get(0));
        assertTrue(hints.get(0).contains("Enter"), hints.get(0));
    }
}
