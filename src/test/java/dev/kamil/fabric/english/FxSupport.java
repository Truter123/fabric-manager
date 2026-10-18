package dev.kamil.fabric.english;

import dev.kamil.fabric.FxHarness;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Assumptions;

/** Harness shared by the english window end-to-end tests: FX thread access, modal dialog handling, stderr capture. */
final class FxSupport {

    private static final ByteArrayOutputStream ERR = new ByteArrayOutputStream();
    private static PrintStream realErr;

    private FxSupport() {
    }

    static void startFx() {
        Assumptions.assumeTrue(FxHarness.displayAvailable(),
                "no display");
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // another test class started it
        }
        Platform.setImplicitExit(false);
        if (realErr == null) {
            realErr = System.err;
            System.setErr(new PrintStream(new java.io.OutputStream() {
                @Override
                public void write(int b) {
                    realErr.write(b);
                    synchronized (ERR) {
                        ERR.write(b);
                    }
                }
            }, true));
        }
    }

    /** Stderr text written since the last call; tests assert it holds no exception. */
    static String takeErr() {
        synchronized (ERR) {
            var text = ERR.toString();
            ERR.reset();
            return text;
        }
    }

    static <T> T fx(Callable<T> action) throws Exception {
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

    static void fx(Runnable action) throws Exception {
        fx(() -> {
            action.run();
            return null;
        });
    }

    /** Runs without waiting; for actions that open a modal dialog and so do not return until it closes. */
    static void later(Runnable action) {
        Platform.runLater(action);
    }

    static void await(BooleanSupplier condition) throws Exception {
        var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            if (fx(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("condition not reached");
    }

    static void pulse() throws Exception {
        fx(() -> null);
        fx(() -> null);
    }

    /** Call on the FX thread: the showing stage with this title, or null. */
    static Stage stageTitled(String title) {
        for (var w : new ArrayList<>(Window.getWindows())) {
            if (w instanceof Stage s && s.isShowing() && title.equals(s.getTitle())) {
                return s;
            }
        }
        return null;
    }

    /** Waits for a showing stage with this title. */
    static void awaitStage(String title) throws Exception {
        await(() -> stageTitled(title) != null);
    }

    /** Call on the FX thread: the dialog pane of the showing dialog with this header (alert) or title. */
    static DialogPane dialogWith(String headerOrTitle) {
        for (var w : new ArrayList<>(Window.getWindows())) {
            if (w.isShowing() && w.getScene() != null && w.getScene().getRoot() instanceof DialogPane p
                    && (headerOrTitle.equals(p.getHeaderText())
                            || (w instanceof Stage s && headerOrTitle.equals(s.getTitle())))) {
                return p;
            }
        }
        return null;
    }

    static void awaitDialog(String headerOrTitle) throws Exception {
        await(() -> dialogWith(headerOrTitle) != null);
    }

    /** Presses the dialog button with this text; the dialog then closes. */
    static void press(String headerOrTitle, String buttonText) throws Exception {
        fx(() -> {
            var pane = dialogWith(headerOrTitle);
            for (ButtonType t : pane.getButtonTypes()) {
                if (buttonText.equals(t.getText())) {
                    ((Button) pane.lookupButton(t)).fire();
                    return;
                }
            }
            throw new AssertionError("no dialog button " + buttonText);
        });
    }

    static void awaitClosed(String headerOrTitle) throws Exception {
        await(() -> dialogWith(headerOrTitle) == null);
    }

    /** Call on the FX thread. */
    static Button button(Parent root, String text) {
        for (Node n : root.lookupAll(".button")) {
            if (n instanceof Button b && text.equals(b.getText())) {
                return b;
            }
        }
        throw new AssertionError("no button " + text);
    }

    /** Call on the FX thread: texts of all buttons under root. */
    static List<String> buttonTexts(Parent root) {
        var out = new ArrayList<String>();
        for (Node n : root.lookupAll(".button")) {
            if (n instanceof Button b) {
                out.add(b.getText());
            }
        }
        return out;
    }
}
