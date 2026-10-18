package dev.kamil.fabric;

import static dev.kamil.fabric.FxHarness.await;
import static dev.kamil.fabric.FxHarness.fx;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * One close rule: Esc closes each of the app's tool windows. Every window's own E2E test class supplies its case
 * ({@code escapeCase()}) because only it knows how to open that window against temporary stores and stubs.
 */
public class EscapeClosesWindowsE2ETest {

    /** Opens a window against temporary stores under {@code dir} and returns its stage (called off the FX thread). */
    @FunctionalInterface
    public interface Opener {
        Stage open(Path dir) throws Exception;
    }

    /** Undoes what the opener changed outside the window itself (settings, injected factories). */
    @FunctionalInterface
    public interface Cleanup {
        void run() throws Exception;
    }

    /** The argument row of one window: its name, how to open it and how to clean up after it. */
    public static Arguments escapeCase(String name, Opener opener, Cleanup cleanup) {
        return Arguments.of(name, opener, cleanup);
    }

    @BeforeAll
    static void startFx() {
        FxHarness.start();
    }

    @ParameterizedTest(name = "Esc closes the {0} window")
    @MethodSource({
        "dev.kamil.fabric.notes.NotesWindowE2ETest#escapeCase",
        "dev.kamil.fabric.tasks.TasksWindowE2ETest#escapeCase",
        "dev.kamil.fabric.quotes.QuotesWindowE2ETest#escapeCase",
        "dev.kamil.fabric.processes.ProcessesWindowE2ETest#escapeCase",
        "dev.kamil.fabric.bodydouble.BodyDoubleWindowE2ETest#escapeCase",
        "dev.kamil.fabric.water.WaterWindowE2ETest#escapeCase",
        "dev.kamil.fabric.servers.ServersWindowE2ETest#escapeCase"
    })
    void escapeClosesTheWindow(String name, Opener opener, Cleanup cleanup, @TempDir Path dir) throws Exception {
        Stage stage = null;
        try {
            stage = opener.open(dir);
            var shown = stage;
            assertTrue(fx(shown::isShowing), name + " is showing");

            fx(() -> {
                var scene = shown.getScene();
                var target = scene.getFocusOwner() != null ? scene.getFocusOwner() : scene.getRoot();
                target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false,
                        false));
                return null;
            });

            await(() -> !shown.isShowing());
        } finally {
            if (stage != null) {
                var left = stage;
                fx(() -> {
                    left.hide();
                    return null;
                });
            }
            cleanup.run();
        }
    }
}
