package dev.kamil.fabric;

import static dev.kamil.fabric.FxHarness.await;
import static dev.kamil.fabric.FxHarness.dialog;
import static dev.kamil.fabric.FxHarness.fx;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A confirm dialog must not confirm on Enter: Cancel is the default button. */
class DialogsE2ETest {

    @BeforeAll
    static void startFx() {
        FxHarness.start();
    }

    @AfterEach
    void closeLeftovers() throws Exception {
        FxHarness.closeDialogs();
    }

    private static CompletableFuture<Boolean> ask() throws Exception {
        var answer = new CompletableFuture<Boolean>();
        Platform.runLater(() -> answer.complete(Dialogs.confirm(null, "Delete it?", "It is gone for good.", "Delete")));
        await(() -> dialog() != null);
        return answer;
    }

    /** What the scene does for the key: the default button (Enter) or the cancel button (Esc) registers it. */
    private static void key(KeyCode code) throws Exception {
        fx(() -> {
            var action = dialog().getScene().getAccelerators().get(new KeyCodeCombination(code));
            assertTrue(action != null, "no handler for " + code);
            action.run();
            return null;
        });
    }

    @Test
    void enterReturnsCancel() throws Exception {
        var answer = ask();
        key(KeyCode.ENTER);
        assertFalse(answer.get(10, TimeUnit.SECONDS));
    }

    @Test
    void escapeReturnsCancel() throws Exception {
        var answer = ask();
        key(KeyCode.ESCAPE);
        assertFalse(answer.get(10, TimeUnit.SECONDS));
    }

    @Test
    void cancelIsTheDefaultButtonAndTheActionIsPlain() throws Exception {
        var answer = ask();
        fx(() -> {
            var pane = dialog();
            var cancel = (Button) pane.lookupButton(ButtonType.CANCEL);
            assertTrue(cancel.isDefaultButton());
            assertTrue(cancel.isCancelButton());
            for (var type : pane.getButtonTypes()) {
                if (type.getText().equals("Delete")) {
                    assertFalse(((Button) pane.lookupButton(type)).isDefaultButton());
                }
            }
            return null;
        });
        FxHarness.press("Cancel");
        assertFalse(answer.get(10, TimeUnit.SECONDS));
    }

    @Test
    void clickingTheActionStillConfirms() throws Exception {
        var answer = ask();
        FxHarness.press("Delete");
        assertTrue(answer.get(10, TimeUnit.SECONDS));
    }
}
