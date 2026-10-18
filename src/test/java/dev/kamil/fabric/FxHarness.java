package dev.kamil.fabric;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;
import org.junit.jupiter.api.Assumptions;

/** Shared bits of the JavaFX tests: start the toolkit once, run on the FX thread, wait, answer a dialog. */
public final class FxHarness {

    private FxHarness() {
    }

    /** True when the tests run on the JavaFX headless glass platform (the default; see {@code -PshowWindows}). */
    public static boolean headless() {
        return "Headless".equalsIgnoreCase(System.getProperty("glass.platform"));
    }

    /** True on the headless platform or with a real display. */
    public static boolean displayAvailable() {
        return headless() || System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null;
    }

    /** Starts the toolkit once; the test class is skipped without a display. */
    public static void start() {
        Assumptions.assumeTrue(displayAvailable(),
                "no display");
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // another test class started it
        }
        Platform.setImplicitExit(false);
    }

    public static <T> T fx(Callable<T> action) throws Exception {
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

    /** Polls the condition on the FX thread for up to ten seconds. */
    public static void await(BooleanSupplier condition) throws Exception {
        var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            if (fx(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("condition not reached");
    }

    /** The pane of the showing dialog, or null; call on the FX thread. */
    public static DialogPane dialog() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w.getScene() != null && w.getScene().getRoot() instanceof DialogPane p) {
                return p;
            }
        }
        return null;
    }

    /** Hides every showing dialog, so one failed test cannot leave a dialog behind for the next. */
    public static void closeDialogs() throws Exception {
        fx(() -> {
            for (Window w : new java.util.ArrayList<>(Window.getWindows())) {
                if (w.isShowing() && w.getScene() != null && w.getScene().getRoot() instanceof DialogPane) {
                    w.hide();
                }
            }
            return null;
        });
    }

    /** Fires the button with this text in the showing dialog. */
    public static void press(String text) throws Exception {
        fx(() -> {
            var pane = dialog();
            for (var type : pane.getButtonTypes()) {
                if (type.getText().equals(text)) {
                    ((Button) pane.lookupButton(type)).fire();
                    return null;
                }
            }
            throw new AssertionError("no button " + text);
        });
    }
}
