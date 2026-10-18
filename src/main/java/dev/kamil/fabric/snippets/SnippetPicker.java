package dev.kamil.fabric.snippets;

import dev.kamil.fabric.Dialogs;
import dev.kamil.fabric.UndoBar;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;

/** Command-palette popup that sends a saved snippet to the active terminal. */
public final class SnippetPicker {

    private final Stage stage = new Stage(StageStyle.UNDECORATED);
    private final SnippetStore store;
    private final Consumer<String> sender;
    private final Supplier<String> currentProjectPath;
    private final TextField filter = new TextField();
    private final ListView<Snippet> list = new ListView<>();

    private final VBox pickerPane = new VBox(8);
    private final VBox editorPane = new VBox(8);
    private final TextField nameField = new TextField();
    private final TextArea textArea = new TextArea();
    private final Label editorTitle = new Label();
    private final Label editorError = new Label();
    private final CheckBox onlyProject = new CheckBox("only this project");
    private final StackPane root = new StackPane();
    private final UndoBar undoBar = new UndoBar();
    private Snippet editing;

    /**
     * @param sender receives the text to type into the active terminal
     * @param currentProjectPath the active project's path, or null; snippets are scoped by project path,
     *        the key of the project table
     */
    public SnippetPicker(Window owner, SnippetStore store, Consumer<String> sender,
            Supplier<String> currentProjectPath) {
        this.store = store;
        this.sender = sender;
        this.currentProjectPath = currentProjectPath;
        stage.initOwner(owner);

        filter.setPromptText("Filter snippets");
        filter.textProperty().addListener((o, a, b) -> refresh());
        list.setCellFactory(v -> new SnippetCell());
        list.setPlaceholder(new Label("No snippets. Ctrl+N creates one."));
        list.setPrefHeight(280);
        VBox.setVgrow(list, Priority.ALWAYS);
        var hint = new Label("Enter send   Shift+Enter without newline   Ctrl+N new   Ctrl+E edit   Ctrl+Del delete");
        hint.getStyleClass().add("snippet-hint");
        pickerPane.getChildren().addAll(filter, list, undoBar, hint);

        nameField.setPromptText("Name");
        textArea.setPromptText("Text");
        textArea.setPrefRowCount(6);
        VBox.setVgrow(textArea, Priority.ALWAYS);
        nameField.setOnAction(e -> saveEditor());
        nameField.textProperty().addListener((o, was, now) -> showEditorError(null));
        textArea.textProperty().addListener((o, was, now) -> showEditorError(null));
        editorTitle.getStyleClass().add("snippet-name");
        editorError.getStyleClass().add("inline-error");
        showEditorError(null);
        var save = new Button("Save");
        save.getStyleClass().add("primary");
        save.setOnAction(e -> saveEditor());
        var cancel = new Button("Cancel");
        cancel.setOnAction(e -> closeEditor());
        var buttons = new HBox(8, new Region(), save, cancel);
        HBox.setHgrow(buttons.getChildren().get(0), Priority.ALWAYS);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        var editorHint = new Label("Ctrl+Enter saves   Esc cancels");
        editorHint.getStyleClass().add("snippet-hint");
        editorPane.getChildren().addAll(editorTitle, nameField, textArea, onlyProject, editorError, buttons, editorHint);
        editorPane.setVisible(false);

        root.getChildren().addAll(pickerPane, editorPane);
        root.getStyleClass().add("snippet-picker");
        root.setPadding(new Insets(10));
        root.setPrefSize(560, 380);

        var scene = new Scene(root);
        scene.getStylesheets().add(Dialogs.class.getResource("/app.css").toExternalForm());
        scene.getStylesheets().add(SnippetPicker.class.getResource("/snippets.css").toExternalForm());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        stage.setScene(scene);
        stage.focusedProperty().addListener((o, was, now) -> {
            if (!now && !editorPane.isVisible()) {
                stage.hide();
            }
        });
    }

    /** Shows the popup centred on the owner window. */
    public void show() {
        closeEditor();
        undoBar.dismiss();
        filter.clear();
        refresh();
        var owner = stage.getOwner();
        stage.setOnShown(e -> {
            stage.setX(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2);
            stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 3);
            filter.requestFocus();
        });
        stage.show();
    }

    private void refresh() {
        var query = filter.getText() == null ? "" : filter.getText();
        var all = store.list(currentProjectPath.get());
        List<Snippet> shown;
        if (query.isBlank()) {
            shown = all;
        } else {
            shown = all.stream()
                    .filter(s -> Fuzzy.score(query, s.name(), s.text()) >= 0)
                    .sorted(Comparator.comparingInt((Snippet s) -> Fuzzy.score(query, s.name(), s.text())).reversed())
                    .toList();
        }
        list.getItems().setAll(shown);
        if (!shown.isEmpty()) {
            list.getSelectionModel().select(0);
        }
    }

    private void onKey(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) {
            if (editorPane.isVisible()) {
                closeEditor();
            } else {
                Dialogs.closeLater(stage);
            }
            event.consume();
            return;
        }
        if (editorPane.isVisible()) {
            if (event.getCode() == KeyCode.ENTER && event.isControlDown()) {
                saveEditor();
                event.consume();
            }
            return;
        }
        switch (event.getCode()) {
            case ENTER -> {
                send(!event.isShiftDown());
                event.consume();
            }
            case DOWN -> {
                move(1);
                event.consume();
            }
            case UP -> {
                move(-1);
                event.consume();
            }
            case N -> {
                if (event.isControlDown()) {
                    openEditor(null);
                    event.consume();
                }
            }
            case E -> {
                if (event.isControlDown() && list.getSelectionModel().getSelectedItem() != null) {
                    openEditor(list.getSelectionModel().getSelectedItem());
                    event.consume();
                }
            }
            case DELETE -> {
                var selected = list.getSelectionModel().getSelectedItem();
                if (event.isControlDown() && selected != null) {
                    if (Dialogs.attempt(stage, "Cannot delete the snippet", () -> store.delete(selected.id()))) {
                        undoBar.offer(UndoBar.deleted(selected.name()), () -> {
                            Dialogs.attempt(stage, "Cannot restore the snippet", () -> store.restore(selected));
                            refresh();
                        });
                    }
                    refresh();
                    event.consume();
                }
            }
            default -> {
            }
        }
    }

    private void move(int delta) {
        int next = Math.max(0, Math.min(list.getItems().size() - 1, list.getSelectionModel().getSelectedIndex() + delta));
        list.getSelectionModel().select(next);
        list.scrollTo(next);
    }

    private void send(boolean newline) {
        var selected = list.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        Dialogs.logFailure(() -> store.markUsed(selected.id()));
        Platform.runLater(() -> {
            stage.hide();
            sender.accept(newline ? selected.text() + "\n" : selected.text());
        });
    }

    private void openEditor(Snippet snippet) {
        editing = snippet;
        editorTitle.setText(snippet == null ? "New snippet" : "Edit snippet");
        nameField.setText(snippet == null ? "" : snippet.name());
        textArea.setText(snippet == null ? "" : snippet.text());
        showEditorError(null);
        onlyProject.setSelected(snippet != null && snippet.projectPath() != null);
        onlyProject.setDisable(currentProjectPath.get() == null && (snippet == null || snippet.projectPath() == null));
        pickerPane.setVisible(false);
        editorPane.setVisible(true);
        Platform.runLater(nameField::requestFocus);
    }

    private void saveEditor() {
        var name = nameField.getText().strip();
        var text = textArea.getText();
        if (name.isEmpty() || text == null || text.isBlank()) {
            showEditorError("Name and text are required");
            return;
        }
        String project = null;
        if (onlyProject.isSelected()) {
            project = editing != null && editing.projectPath() != null ? editing.projectPath() : currentProjectPath.get();
        }
        var target = project;
        if (!Dialogs.attempt(stage, "Cannot save the snippet", () -> {
            if (editing == null) {
                store.add(name, text, target);
            } else {
                store.update(editing.id(), name, text, target);
            }
        })) {
            return;
        }
        closeEditor();
        refresh();
    }

    /** Shows {@code message} under the fields, or hides the error line when it is null. */
    private void showEditorError(String message) {
        editorError.setText(message == null ? "" : message);
        editorError.setVisible(message != null);
        editorError.setManaged(message != null);
    }

    private void closeEditor() {
        editing = null;
        editorPane.setVisible(false);
        pickerPane.setVisible(true);
        Platform.runLater(filter::requestFocus);
    }

    private static final class SnippetCell extends ListCell<Snippet> {
        @Override
        protected void updateItem(Snippet snippet, boolean empty) {
            super.updateItem(snippet, empty);
            if (empty || snippet == null) {
                setGraphic(null);
                return;
            }
            var name = new Label(snippet.name());
            name.getStyleClass().add("snippet-name");
            var preview = new Label(snippet.firstLine());
            preview.getStyleClass().add("snippet-preview");
            var row = new HBox(10, name, preview);
            row.setAlignment(Pos.CENTER_LEFT);
            if (snippet.projectPath() != null) {
                var badge = new Label("project");
                badge.getStyleClass().add("snippet-badge");
                var spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                row.getChildren().addAll(spacer, badge);
            }
            setGraphic(row);
        }
    }
}
