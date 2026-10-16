package dev.kamil.fabric;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;

/** Small dark-themed alerts. */
public final class Dialogs {

    private Dialogs() {
    }

    /**
     * Hides a window after the current native key event has finished. Closing it synchronously from a key
     * handler can crash glass GTK (IBus key filtering still dispatches into the freed window).
     */
    public static void closeLater(Window window) {
        Platform.runLater(window::hide);
    }

    /** Asks before a destructive action; true when the user picked {@code action}. */
    public static boolean confirm(Window owner, String header, String text, String action) {
        var ok = new ButtonType(action, ButtonBar.ButtonData.OK_DONE);
        var alert = new Alert(Alert.AlertType.NONE, text, ok, ButtonType.CANCEL);
        alert.setTitle("FabricManager");
        alert.setHeaderText(header);
        style(alert.getDialogPane());
        alert.initOwner(owner);
        return alert.showAndWait().filter(ok::equals).isPresent();
    }

    public static void error(Window owner, String header, String text) {
        var alert = new Alert(Alert.AlertType.NONE, text, ButtonType.OK);
        alert.setTitle("FabricManager");
        alert.setHeaderText(header);
        style(alert.getDialogPane());
        alert.initOwner(owner);
        alert.showAndWait();
    }

    /** Runs a user action; a failure is shown to the user. Returns true when the action succeeded. */
    public static boolean attempt(Window owner, String header, Runnable action) {
        try {
            action.run();
            return true;
        } catch (RuntimeException e) {
            error(owner, header, e.getMessage());
            return false;
        }
    }

    /** Runs a background or shutdown action; a failure is logged and never propagates. */
    public static void logFailure(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
        }
    }

    public static void style(DialogPane pane) {
        pane.getStylesheets().add(Dialogs.class.getResource("/app.css").toExternalForm());
        pane.getStyleClass().add("picker");
    }
}
