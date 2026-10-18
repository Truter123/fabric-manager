package dev.kamil.fabric.english;

import java.util.List;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Fixed chrome of a card screen, one per pane: a header (Home (Esc) and a title), a {@link #content()} slot that the
 * pane swaps between front, back and end screens, and a nav bar (Back (←), Next (→), Remove (Del) at the far right).
 * Everything sits in a reading column of at most {@value #COLUMN_WIDTH} px, centred in the window.
 */
final class CardFrame extends VBox {

    static final double COLUMN_WIDTH = 900;
    private static final double PADDING = 24;
    private static final String PRIMARY = "primary";

    private final Button home = new Button("Home (Esc)");
    private final Button back = new Button("Back (←)");
    private final Button next = new Button("Next (→)");
    private final Button remove = new Button("Remove (Del)");
    private final Label title = new Label();
    private final VBox content = new VBox(16);
    private Button primary;

    /** The buttons run the given actions; the pane decides what they mean on each screen. */
    CardFrame(Runnable onHome, Runnable onBack, Runnable onNext, Runnable onRemove) {
        for (var b : List.of(home, back, next, remove)) {
            b.setFocusTraversable(false);
        }
        home.setOnAction(e -> onHome.run());
        back.setOnAction(e -> onBack.run());
        next.setOnAction(e -> onNext.run());
        remove.setOnAction(e -> onRemove.run());
        title.getStyleClass().add("history-meta");

        var header = new HBox(12, home, title);
        header.setAlignment(Pos.CENTER_LEFT);
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var nav = new HBox(8, back, next, spacer, remove);
        nav.setAlignment(Pos.CENTER_LEFT);
        VBox.setVgrow(content, Priority.ALWAYS);

        var column = new VBox(16, header, content, nav);
        column.setPadding(new Insets(PADDING));
        column.setMaxWidth(COLUMN_WIDTH + 2 * PADDING);
        VBox.setVgrow(column, Priority.ALWAYS);

        // Min width 0: otherwise unwrapped text sets a min width wider than the window and the centred column is
        // clipped on the left; the wrapping width then follows the window width.
        setMinWidth(0);
        column.setMinWidth(0);
        content.setMinWidth(0);
        setAlignment(Pos.TOP_CENTER);
        getChildren().add(column);
    }

    /** The slot between header and nav bar; its width is the text width of the pane. */
    VBox content() {
        return content;
    }

    /** Replaces what the slot shows. */
    void setContent(Node... nodes) {
        content.getChildren().setAll(nodes);
    }

    void setTitle(String text) {
        title.setText(text);
    }

    Button homeButton() {
        return home;
    }

    Button backButton() {
        return back;
    }

    Button nextButton() {
        return next;
    }

    Button removeButton() {
        return remove;
    }

    /** Highlights {@code button} as the one primary action and clears the previous one; null clears it. */
    void setPrimary(Button button) {
        if (primary != null) {
            primary.getStyleClass().remove(PRIMARY);
        }
        primary = button;
        if (button != null && !button.getStyleClass().contains(PRIMARY)) {
            button.getStyleClass().add(PRIMARY);
        }
    }
}
