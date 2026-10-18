package dev.kamil.fabric;

import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.util.Duration;

/**
 * Timed "Deleted X, Undo" strip shown after a delete. The delete has already happened when it shows:
 * Undo puts the item back, and when the time runs out the delete is final. Hidden until {@link #offer}.
 */
public final class UndoBar extends HBox {

    private static final Duration TIME = Duration.seconds(8);
    private static final int NAME_LIMIT = 30;

    private final Label label = new Label();
    private final PauseTransition timer;
    private Runnable restore;

    public UndoBar() {
        this(TIME);
    }

    UndoBar(Duration time) {
        super(10);
        timer = new PauseTransition(time);
        getStyleClass().add("undo-bar");
        setAlignment(Pos.CENTER_LEFT);
        var undo = new Button("Undo");
        undo.setOnAction(e -> undo());
        getChildren().addAll(label, undo);
        timer.setOnFinished(e -> dismiss());
        setVisible(false);
        setManaged(false);
    }

    /** "Deleted name" on one line, a long name cut short. */
    public static String deleted(String name) {
        var one = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (one.isEmpty()) {
            return "Deleted";
        }
        return "Deleted " + (one.length() > NAME_LIMIT ? one.substring(0, NAME_LIMIT - 1) + "…" : one);
    }

    /** Shows the message with an Undo button for a few seconds; a new offer replaces the pending one. */
    public void offer(String message, Runnable restore) {
        this.restore = restore;
        label.setText(message);
        setVisible(true);
        setManaged(true);
        timer.playFromStart();
    }

    /** Hides the bar; whatever was offered stays deleted. */
    public void dismiss() {
        timer.stop();
        restore = null;
        setVisible(false);
        setManaged(false);
    }

    private void undo() {
        var action = restore;
        dismiss();
        if (action != null) {
            action.run();
        }
    }
}
