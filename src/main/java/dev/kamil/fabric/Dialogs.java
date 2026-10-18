package dev.kamil.fabric;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Cell;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

/** Small dark-themed alerts. */
public final class Dialogs {

    private static final String KEEP_TEXT_ON_ESCAPE = "dev.kamil.fabric.keepTextOnEscape";

    private Dialogs() {
    }

    /**
     * Hides a window after the current native key event has finished. Closing it synchronously from a key
     * handler can crash glass GTK (IBus key filtering still dispatches into the freed window).
     */
    public static void closeLater(Window window) {
        Platform.runLater(window::hide);
    }

    /**
     * The muted one-line footer that lists the keys of a window, "Enter send   Esc close": a key and its action,
     * pairs separated by three spaces. It carries {@code snippet-hint} like the pickers' hint and {@code key-hint},
     * which app.css styles for the windows that do not load snippets.css.
     */
    public static Label keyHint(String text) {
        var hint = new Label(text);
        hint.getStyleClass().addAll("snippet-hint", "key-hint");
        return hint;
    }

    /**
     * Esc closes the window the way its X button does, so a close-request guard still runs. A text field with
     * text is cleared first, like a search box, unless it was marked with {@link #escapeKeepsText} or is a
     * spinner's editor. A filter, because a focused list or table consumes Esc itself; it steps aside only while
     * the focused control needs Esc: an open combo or menu popup, or a cell being edited.
     */
    public static void closeOnEscape(Scene scene, Stage stage) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            var focus = scene.getFocusOwner();
            if (e.getCode() != KeyCode.ESCAPE || ownsEscape(focus)) {
                return;
            }
            if (focus instanceof TextField field && !field.getText().isEmpty()
                    && !field.getProperties().containsKey(KEEP_TEXT_ON_ESCAPE) && !within(field, Spinner.class)) {
                field.clear();
            } else {
                // Not synchronously, see closeLater. An unconsumed close request hides the stage.
                Platform.runLater(() -> stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)));
            }
            e.consume();
        });
    }

    /** True while the focused control uses Esc itself: an open combo or menu popup, or a cell being edited. */
    private static boolean ownsEscape(Node focus) {
        for (var n = focus; n != null; n = n.getParent()) {
            if (n instanceof ComboBoxBase<?> c && c.isShowing() || n instanceof MenuButton m && m.isShowing()
                    || n instanceof Cell<?> cell && cell.isEditing()) {
                return true;
            }
        }
        return false;
    }

    private static boolean within(Node node, Class<?> type) {
        for (var n = node.getParent(); n != null; n = n.getParent()) {
            if (type.isInstance(n)) {
                return true;
            }
        }
        return false;
    }

    /** Esc in this field closes the window even when the field has text: its text is content, not a filter. */
    public static void escapeKeepsText(TextField field) {
        field.getProperties().put(KEEP_TEXT_ON_ESCAPE, Boolean.TRUE);
    }

    /**
     * Asks before a destructive action; true when the user picked {@code action}. Cancel is the default
     * button, so Enter never confirms; the action button is plain.
     */
    public static boolean confirm(Window owner, String header, String text, String action) {
        var ok = new ButtonType(action, ButtonBar.ButtonData.OK_DONE);
        var alert = new Alert(Alert.AlertType.NONE, text, ok, ButtonType.CANCEL);
        alert.setTitle("FabricManager");
        alert.setHeaderText(header);
        var pane = alert.getDialogPane();
        style(pane);
        var cancel = (Button) pane.lookupButton(ButtonType.CANCEL);
        ((Button) pane.lookupButton(ok)).setDefaultButton(false);
        cancel.setDefaultButton(true);
        alert.setOnShown(e -> cancel.requestFocus());
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
