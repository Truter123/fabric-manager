package dev.kamil.fabric.quotes;

import dev.kamil.fabric.Dialogs;
import dev.kamil.fabric.session.Database;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Non-modal window where the user adds, edits and deletes their own quotes. */
public final class QuotesWindow {

    private static QuotesWindow instance;

    private final Stage window = new Stage();
    private final QuoteStore store;
    private final Runnable onChange;
    private final javafx.collections.ObservableList<Quote> rows = FXCollections.observableArrayList();
    private final ListView<Quote> list = new ListView<>(rows);

    /** Opens the window, or brings the existing one to front. */
    public static void open(Window owner, Database db, Runnable onChange) {
        if (instance != null) {
            instance.window.show();
            instance.window.setIconified(false);
            instance.window.toFront();
            instance.window.requestFocus();
            return;
        }
        var created = new QuotesWindow(owner, db, onChange);
        instance = created;
        created.window.setOnHidden(e -> instance = null);
        created.window.show();
    }

    private QuotesWindow(Window owner, Database db, Runnable onChange) {
        store = new QuoteStore(db);
        this.onChange = onChange == null ? () -> { } : onChange;
        window.initOwner(owner);
        window.setTitle("Quotes");

        var add = new Button("Add…");
        var edit = new Button("Edit…");
        var delete = new Button("Delete…");
        add.setOnAction(e -> add());
        edit.setOnAction(e -> edit());
        delete.setOnAction(e -> delete());
        edit.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        delete.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        var bar = new HBox(8, add, edit, delete);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8));

        list.setPlaceholder(new Label("No quotes yet. Click Add to write your first one."));
        list.setCellFactory(v -> new QuoteCell());

        var root = new BorderPane(list);
        root.setTop(bar);
        var scene = new Scene(root, 700, 500);
        scene.getStylesheets().add(QuotesWindow.class.getResource("/app.css").toExternalForm());
        window.setScene(scene);
        refresh();
    }

    private void refresh() {
        rows.setAll(store.all());
    }

    private void add() {
        var entered = ask("Add quote", "", "");
        if (entered == null) {
            return;
        }
        if (Dialogs.attempt(window, "Cannot add quote", () -> store.add(entered[0], entered[1]))) {
            changed();
        }
    }

    private void edit() {
        var quote = list.getSelectionModel().getSelectedItem();
        if (quote == null) {
            return;
        }
        var entered = ask("Edit quote", quote.text(), quote.author() == null ? "" : quote.author());
        if (entered == null) {
            return;
        }
        if (Dialogs.attempt(window, "Cannot update quote", () -> store.update(quote.id(), entered[0], entered[1]))) {
            changed();
        }
    }

    private void delete() {
        var quote = list.getSelectionModel().getSelectedItem();
        if (quote == null || !Dialogs.confirm(window, "Delete this quote?", quote.text(), "Delete")) {
            return;
        }
        if (Dialogs.attempt(window, "Cannot delete quote", () -> store.delete(quote.id()))) {
            changed();
        }
    }

    private void changed() {
        refresh();
        onChange.run();
    }

    /** Text and author from the user, or null when cancelled or the text is empty. */
    private String[] ask(String title, String text, String author) {
        var dialog = new Dialog<ButtonType>();
        dialog.setTitle(title);
        dialog.initOwner(window);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Dialogs.style(dialog.getDialogPane());
        var textArea = new TextArea(text);
        textArea.setWrapText(true);
        textArea.setPrefRowCount(4);
        textArea.setPromptText("Quote");
        var authorField = new TextField(author);
        authorField.setPromptText("Author");
        var grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.addRow(0, new Label("Quote"), textArea);
        grid.addRow(1, new Label("Author"), authorField);
        dialog.getDialogPane().setContent(grid);
        var result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK || textArea.getText().isBlank()) {
            return null;
        }
        return new String[] {textArea.getText().strip(), authorField.getText().strip()};
    }

    private final class QuoteCell extends ListCell<Quote> {
        QuoteCell() {
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && getItem() != null) {
                    edit();
                }
            });
        }

        @Override
        protected void updateItem(Quote quote, boolean empty) {
            super.updateItem(quote, empty);
            if (empty || quote == null) {
                setText(null);
                return;
            }
            setText("“" + quote.text() + "”"
                    + (quote.author() == null || quote.author().isBlank() ? "" : " — " + quote.author()));
            setWrapText(true);
        }
    }
}
