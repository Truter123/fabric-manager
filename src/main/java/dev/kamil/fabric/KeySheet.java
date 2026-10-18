package dev.kamil.fabric;

import java.util.List;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/**
 * The F1 cheat sheet: one row per {@link Shortcut}, then the keys only a focused terminal handles, key and what
 * it does. F1 or Esc closes it.
 */
public final class KeySheet {

    /** A key of the focused terminal (TerminalTab handles them, not the main window), as the sheet shows it. */
    record TerminalKey(String keys, String what) {
    }

    /** The keys that work only inside a terminal, in the order the sheet lists them; the README table has them too. */
    static final List<TerminalKey> TERMINAL_KEYS = List.of(
            new TerminalKey("Ctrl+Shift+D", "split: shell beside the session (again = switch orientation)"),
            new TerminalKey("Ctrl+Shift+[ / Ctrl+Shift+]", "move focus between the two panes of a split"),
            new TerminalKey("Shift+Tab", "passed to the tool (Claude mode switch)"),
            new TerminalKey("Shift+Enter", "new line in the prompt"));

    private static Stage open;

    private KeySheet() {
    }

    /** Shows the sheet, or hides it when it is already showing. */
    public static void toggle(Window owner) {
        if (open != null) {
            Dialogs.closeLater(open);
            return;
        }
        var stage = new Stage(StageStyle.UTILITY);
        stage.initOwner(owner);
        stage.setTitle("Keys");
        var grid = grid();
        int row = 0;
        for (var shortcut : Shortcut.values()) {
            grid.addRow(row++, keyLabel(shortcut.keys()), whatLabel(shortcut.what()));
        }
        var terminal = grid();
        row = 0;
        for (var key : TERMINAL_KEYS) {
            terminal.addRow(row++, keyLabel(key.keys()), whatLabel(key.what()));
        }
        var section = new Label("In a terminal");
        section.getStyleClass().add("keysheet-section");
        var scroll = new ScrollPane(new VBox(18, grid, new VBox(8, section, terminal)));
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("keysheet-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        var title = new Label("Keys");
        title.getStyleClass().add("keysheet-title");
        var root = new VBox(12, title, scroll, Dialogs.keyHint("F1 close   Esc close"));
        root.getStyleClass().add("keysheet");
        root.setPadding(new Insets(16));
        var scene = new Scene(root, 560, 620);
        scene.getStylesheets().add(KeySheet.class.getResource("/app.css").toExternalForm());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (Shortcut.KEYS.combo().match(e)) {
                Dialogs.closeLater(stage);
                e.consume();
            }
        });
        Dialogs.closeOnEscape(scene, stage);
        stage.setScene(scene);
        stage.setOnHidden(e -> open = null);
        open = stage;
        stage.show();
    }

    private static GridPane grid() {
        var grid = new GridPane();
        grid.setHgap(18);
        grid.setVgap(5);
        return grid;
    }

    private static Label keyLabel(String text) {
        var label = new Label(text);
        label.getStyleClass().add("keysheet-key");
        return label;
    }

    private static Label whatLabel(String text) {
        var label = new Label(text);
        label.getStyleClass().add("keysheet-what");
        return label;
    }

    /** The showing sheet, or null; for tests. */
    static Stage openStage() {
        return open;
    }
}
