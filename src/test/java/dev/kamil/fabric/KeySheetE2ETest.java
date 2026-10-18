package dev.kamil.fabric;

import static dev.kamil.fabric.FxHarness.await;
import static dev.kamil.fabric.FxHarness.fx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** F1 opens a sheet with one row per shortcut and a section of terminal keys; F1 again, or Esc, closes it. */
class KeySheetE2ETest {

    private Stage owner;

    @BeforeAll
    static void startFx() {
        FxHarness.start();
    }

    @BeforeEach
    void open() throws Exception {
        fx(() -> {
            owner = new Stage();
            owner.setScene(new Scene(new StackPane(), 200, 100));
            owner.show();
            KeySheet.toggle(owner);
            return null;
        });
    }

    @AfterEach
    void close() throws Exception {
        fx(() -> {
            if (KeySheet.openStage() != null) {
                KeySheet.openStage().hide();
            }
            owner.hide();
            return null;
        });
    }

    @Test
    void listsEveryShortcutKeyAndWhatItDoes() throws Exception {
        var keys = fx(() -> KeySheet.openStage().getScene().getRoot().lookupAll(".keysheet-key").stream()
                .map(n -> ((Label) n).getText()).toList());
        var what = fx(() -> KeySheet.openStage().getScene().getRoot().lookupAll(".keysheet-what").stream()
                .map(n -> ((Label) n).getText()).toList());

        int n = Shortcut.values().length;
        assertEquals(Arrays.stream(Shortcut.values()).map(Shortcut::keys).toList(), keys.subList(0, n));
        assertEquals(Arrays.stream(Shortcut.values()).map(Shortcut::what).toList(), what.subList(0, n));
    }

    @Test
    void listsTheTerminalOnlyKeysInTheirOwnSection() throws Exception {
        var root = fx(() -> KeySheet.openStage().getScene().getRoot());
        var section = fx(() -> root.lookupAll(".keysheet-section").stream().map(n -> ((Label) n).getText()).toList());
        var keys = fx(() -> root.lookupAll(".keysheet-key").stream().map(n -> ((Label) n).getText()).toList());
        var what = fx(() -> root.lookupAll(".keysheet-what").stream().map(n -> ((Label) n).getText()).toList());

        int n = Shortcut.values().length;
        assertEquals(List.of("In a terminal"), section);
        assertEquals(KeySheet.TERMINAL_KEYS.stream().map(KeySheet.TerminalKey::keys).toList(),
                keys.subList(n, keys.size()));
        assertEquals(KeySheet.TERMINAL_KEYS.stream().map(KeySheet.TerminalKey::what).toList(),
                what.subList(n, what.size()));
        assertTrue(keys.contains("Ctrl+Shift+D"), keys.toString());
        assertTrue(keys.contains("Ctrl+Shift+[ / Ctrl+Shift+]"), keys.toString());
    }

    @Test
    void toggleAgainClosesIt() throws Exception {
        assertNotNull(fx(KeySheet::openStage));

        fx(() -> {
            KeySheet.toggle(owner);
            return null;
        });

        await(() -> KeySheet.openStage() == null);
        assertNull(fx(KeySheet::openStage));
    }

    @Test
    void f1InsideTheSheetClosesIt() throws Exception {
        fx(() -> {
            var root = KeySheet.openStage().getScene().getRoot();
            root.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F1, false, false, false, false));
            return null;
        });

        await(() -> KeySheet.openStage() == null);
    }
}
