package dev.kamil.fabric.toast;

import java.util.ArrayList;
import java.util.List;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * In-app notifications: a stack of toasts in the top-right corner of the window it is placed in, newest on top.
 * A toast without actions disappears after a few seconds; one with actions stays until closed or answered.
 * It never takes the focus and never leaves the window. Use from the FX thread only.
 */
public final class Toasts extends VBox {

    public static final int MAX_VISIBLE = 4;
    private static final double AUTO_DISMISS_SECONDS = 8;

    /** A button on a toast; clicking it runs the action and removes the toast. */
    public record Action(String label, Runnable run) {
    }

    /** A shown toast. */
    public interface Handle {
        /** Removes the toast; runs its on-closed callback unless an action was clicked. Safe to call twice. */
        void close();
    }

    private final List<Handle> open = new ArrayList<>();

    public Toasts() {
        super(8);
        getStyleClass().add("toasts");
        setAlignment(Pos.TOP_RIGHT);
        setPickOnBounds(false);
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
    }

    /** A plain toast that goes away by itself. */
    public Handle show(String title, String text) {
        return show(title, text, List.of(), null);
    }

    /**
     * Shows a toast on top. With actions it stays until closed or answered; {@code onClosed} runs when it is
     * closed, replaced or pushed out, not when an action was clicked.
     */
    public Handle show(String title, String text, List<Action> actions, Runnable onClosed) {
        var card = new VBox(4);
        card.getStyleClass().add("toast");
        var titleLabel = new Label(title);
        titleLabel.getStyleClass().add("toast-title");
        titleLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(titleLabel, Priority.ALWAYS);
        var close = new Button("×");
        close.getStyleClass().add("toast-close");
        close.setFocusTraversable(false);
        var header = new HBox(8, titleLabel, close);
        header.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(header);
        if (text != null && !text.isBlank()) {
            var body = new Label(text);
            body.getStyleClass().add("toast-text");
            body.setWrapText(true);
            card.getChildren().add(body);
        }
        var pause = new PauseTransition(Duration.seconds(AUTO_DISMISS_SECONDS));
        var state = new boolean[1]; // closed
        Handle[] self = new Handle[1];
        self[0] = new Handle() {
            @Override
            public void close() {
                finish(card, pause, state, onClosed, true, this);
            }
        };
        close.setOnAction(e -> self[0].close());
        if (!actions.isEmpty()) {
            var row = new HBox(8);
            row.setAlignment(Pos.CENTER_RIGHT);
            for (var action : actions) {
                var button = new Button(action.label());
                button.setFocusTraversable(false);
                button.setOnAction(e -> {
                    finish(card, pause, state, onClosed, false, self[0]);
                    action.run().run();
                });
                row.getChildren().add(button);
            }
            card.getChildren().add(row);
        } else {
            pause.setOnFinished(e -> self[0].close());
            pause.play();
        }
        getChildren().add(0, card);
        open.add(0, self[0]);
        while (open.size() > MAX_VISIBLE) {
            open.get(open.size() - 1).close();
        }
        return self[0];
    }

    private void finish(VBox card, PauseTransition pause, boolean[] closed, Runnable onClosed, boolean notify,
            Handle handle) {
        if (closed[0]) {
            return;
        }
        closed[0] = true;
        pause.stop();
        getChildren().remove(card);
        open.remove(handle);
        if (notify && onClosed != null) {
            onClosed.run();
        }
    }
}
