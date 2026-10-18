package dev.kamil.fabric;

import static dev.kamil.fabric.FxHarness.await;
import static dev.kamil.fabric.FxHarness.fx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.control.Button;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The shared undo strip: shown on offer, Undo restores once, the timeout makes the delete final. */
class UndoBarE2ETest {

    @BeforeAll
    static void startFx() {
        FxHarness.start();
    }

    private static void undo(UndoBar bar) throws Exception {
        fx(() -> {
            ((Button) bar.lookup(".button")).fire();
            return null;
        });
    }

    @Test
    void undoRunsTheRestoreOnceAndHidesTheBar() throws Exception {
        var restored = new AtomicInteger();
        var bar = fx(UndoBar::new);
        assertFalse(fx(bar::isVisible));
        fx(() -> {
            bar.offer("Deleted x", restored::incrementAndGet);
            return null;
        });
        assertTrue(fx(bar::isVisible));
        undo(bar);
        assertEquals(1, restored.get());
        assertFalse(fx(bar::isVisible));
        undo(bar);
        assertEquals(1, restored.get());
    }

    @Test
    void timeoutHidesTheBarWithoutRestoring() throws Exception {
        var restored = new AtomicInteger();
        var bar = fx(() -> new UndoBar(javafx.util.Duration.millis(100)));
        fx(() -> {
            bar.offer("Deleted x", restored::incrementAndGet);
            return null;
        });
        await(() -> !bar.isVisible());
        assertEquals(0, restored.get());
    }

    @Test
    void deletedNamesAreOneShortLine() {
        assertEquals("Deleted a b", UndoBar.deleted("  a\n b "));
        assertEquals("Deleted", UndoBar.deleted("  "));
        assertEquals("Deleted " + "x".repeat(29) + "…", UndoBar.deleted("x".repeat(100)));
    }
}
