package dev.kamil.fabric.notes;

import dev.kamil.fabric.Dialogs;
import dev.kamil.fabric.UndoBar;
import dev.kamil.fabric.session.Database;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.control.SplitPane;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Non-modal notes window: search, filter chips and a note list on the left, an autosaving editor on the right.
 * Ctrl+N new note, Ctrl+Delete in the list deletes (undo bar), Ctrl+P pin.
 */
public final class NotesWindow {

    private static final javafx.util.Duration AUTOSAVE = javafx.util.Duration.seconds(1);
    private static final KeyCombination NEW = new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN);
    private static final KeyCombination DELETE = new KeyCodeCombination(KeyCode.DELETE,
            KeyCombination.CONTROL_DOWN);
    private static final KeyCombination PIN = new KeyCodeCombination(KeyCode.P, KeyCombination.CONTROL_DOWN);

    private static NotesWindow instance;
    /** The corrector command; tests replace it so no real claude CLI runs. */
    static List<String> correctorCommand = EnglishCorrector.standard();

    private final Stage window = new Stage();
    private final NoteStore store;
    private final EnglishCorrector corrector = new EnglishCorrector(correctorCommand);
    private final javafx.collections.ObservableList<Note> notes = FXCollections.observableArrayList();
    private final ListView<Note> list = new ListView<>(notes);
    private final TextField search = new TextField();
    private final FlowPane chips = new FlowPane(6, 6);
    private final ToggleGroup chipGroup = new ToggleGroup();
    private final TextField titleField = new TextField();
    private final TextArea body = new TextArea();
    private final ComboBox<String> project = new ComboBox<>();
    private final Button pinButton = new Button("Pin");
    private final Button fixButton = new Button("Fix English");
    private final Label status = new Label();
    private final UndoBar undoBar = new UndoBar();
    private final VBox fixBox = new VBox(6);
    private final TextArea fixed = new TextArea();
    private final PauseTransition autosave = new PauseTransition(AUTOSAVE);
    private final VBox editor = new VBox(8);

    private Supplier<List<Note>> filter;
    private String filterId = "all";
    private long current = -1;
    private boolean loading;
    private boolean dirty;

    /** Opens the window, or brings the existing one to front. */
    public static void open(Window owner, Database db) {
        if (instance != null) {
            instance.window.show();
            instance.window.setIconified(false);
            instance.window.toFront();
            instance.window.requestFocus();
            return;
        }
        var created = new NotesWindow(owner, db);
        instance = created;
        created.window.setOnHidden(e -> {
            created.flush();
            instance = null;
        });
        created.window.show();
        created.body.requestFocus();
    }

    /** The open window, or null; for tests. */
    static Stage openStage() {
        return instance == null ? null : instance.window;
    }

    private NotesWindow(Window owner, Database db) {
        store = new NoteStore(db);
        window.initOwner(owner);
        window.setTitle("Notes");

        search.setPromptText("Search notes");
        search.textProperty().addListener((o, a, b) -> refresh());
        chips.getStyleClass().add("notes-chips");
        list.setCellFactory(v -> new NoteCell());
        list.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null && b.id() != current) {
                flush();
                load(store.find(b.id()));
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        var side = new VBox(8, search, chips, list);
        side.setPadding(new Insets(8));
        side.getStyleClass().add("notes-side");
        side.setMinWidth(240);

        titleField.setPromptText("Title (optional)");
        body.setPromptText("Write in Markdown. #tags in the text become tags.");
        body.setWrapText(true);
        body.getStyleClass().add("note-body");
        VBox.setVgrow(body, Priority.ALWAYS);
        project.setPromptText("Project");
        project.setCellFactory(v -> new ProjectCell());
        project.setButtonCell(new ProjectCell());
        pinButton.setOnAction(e -> togglePin());
        fixButton.setOnAction(e -> fixEnglish());
        var delete = new Button("Delete");
        delete.setOnAction(e -> deleteCurrent());
        var newButton = new Button("New");
        newButton.setOnAction(e -> newNote());
        newButton.getStyleClass().add("primary");
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var tools = new HBox(8, newButton, pinButton, project, fixButton, spacer, delete);
        tools.setAlignment(Pos.CENTER_LEFT);

        fixed.setWrapText(true);
        fixed.setEditable(false);
        fixed.setPrefRowCount(5);
        var replace = new Button("Replace");
        replace.setOnAction(e -> {
            body.setText(fixed.getText());
            hide(fixBox);
        });
        var dismiss = new Button("Dismiss");
        dismiss.setOnAction(e -> hide(fixBox));
        fixBox.getChildren().addAll(new Label("Corrected English"), fixed, new HBox(8, replace, dismiss));
        fixBox.getStyleClass().add("note-fix");
        hide(fixBox);

        status.getStyleClass().add("note-status");
        editor.getChildren().addAll(tools, titleField, body, fixBox, undoBar, status);
        editor.setPadding(new Insets(8));

        autosave.setOnFinished(e -> flush());
        titleField.textProperty().addListener((o, a, b) -> changed());
        body.textProperty().addListener((o, a, b) -> changed());

        var split = new SplitPane(side, editor);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.34);
        var root = new BorderPane(split);
        root.setBottom(Dialogs.keyHint("Ctrl+N new   Ctrl+P pin   Ctrl+Del delete (in the list)   Esc close"));
        var scene = new Scene(root, 960, 640);
        scene.getStylesheets().add(NotesWindow.class.getResource("/app.css").toExternalForm());
        scene.getStylesheets().add(NotesWindow.class.getResource("/notes.css").toExternalForm());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (NEW.match(e)) {
                newNote();
            } else if (DELETE.match(e) && scene.getFocusOwner() == list) {
                deleteCurrent();
            } else if (PIN.match(e)) {
                togglePin();
            } else {
                return;
            }
            e.consume();
        });
        window.setScene(scene);
        Dialogs.closeOnEscape(scene, window);
        Dialogs.escapeKeepsText(titleField);
        window.setOnCloseRequest(e -> {
            flush();
            if (dirty && !Dialogs.confirm(window, "Close without saving?",
                    "The last edit could not be saved and will be lost.", "Close")) {
                e.consume();
            }
        });

        var paths = FXCollections.<String>observableArrayList();
        paths.add(null);
        paths.addAll(store.projectPaths());
        project.setItems(paths);
        project.setOnAction(e -> {
            if (!loading && current >= 0) {
                Dialogs.attempt(window, "Cannot change the project", () -> store.setProject(current, project.getValue()));
                refresh();
            }
        });
        setFilter("all");
        refresh();
        newNote();
    }

    private static void hide(javafx.scene.Node node) {
        node.setVisible(false);
        node.setManaged(false);
    }

    private static void show(javafx.scene.Node node) {
        node.setVisible(true);
        node.setManaged(true);
    }

    private void changed() {
        if (loading) {
            return;
        }
        dirty = true;
        autosave.playFromStart();
    }

    /** Saves pending edits now; creates the note on the first non-empty edit. */
    private void flush() {
        autosave.stop();
        if (!dirty) {
            return;
        }
        dirty = false;
        var text = body.getText();
        var title = titleField.getText();
        if (current < 0) {
            if (text.isBlank() && title.isBlank()) {
                return;
            }
            try {
                current = store.create(title, text, false);
            } catch (RuntimeException e) {
                failedSave(e);
                return;
            }
        } else {
            try {
                store.update(current, title, text);
            } catch (RuntimeException e) {
                failedSave(e);
                return;
            }
        }
        say("Saved");
        refresh();
    }

    /** Autosave must not interrupt typing: report in red in the status line and keep the edits dirty to retry. */
    private void failedSave(RuntimeException e) {
        System.err.println(e.getMessage());
        dirty = true;
        say("Saving failed");
        status.getStyleClass().add("note-status-error");
    }

    /** Shows a message in the status line in its normal colour. */
    private void say(String text) {
        status.getStyleClass().remove("note-status-error");
        status.setText(text);
    }

    private void load(Note note) {
        loading = true;
        autosave.stop();
        dirty = false;
        hide(fixBox);
        if (note == null) {
            current = -1;
            titleField.clear();
            body.clear();
            project.setValue(null);
            pinButton.setText("Pin");
        } else {
            current = note.id();
            titleField.setText(note.title() == null ? "" : note.title());
            body.setText(note.text());
            project.setValue(note.projectPath());
            pinButton.setText(note.pinned() ? "Unpin" : "Pin");
        }
        loading = false;
    }

    private void newNote() {
        flush();
        list.getSelectionModel().clearSelection();
        load(null);
        say("");
        titleField.requestFocus();
    }

    private void togglePin() {
        flush();
        if (current < 0) {
            return;
        }
        var note = store.find(current);
        if (note == null) {
            return;
        }
        if (!Dialogs.attempt(window, "Cannot pin the note", () -> store.setPinned(current, !note.pinned()))) {
            return;
        }
        pinButton.setText(note.pinned() ? "Pin" : "Unpin");
        refresh();
    }

    private void deleteCurrent() {
        flush();
        if (current < 0) {
            return;
        }
        var note = store.find(current);
        if (!Dialogs.attempt(window, "Cannot delete the note", () -> store.delete(current))) {
            return;
        }
        load(null);
        refresh();
        if (note != null) {
            undoBar.offer(UndoBar.deleted(note.displayTitle()), () -> restore(note));
        }
    }

    private void restore(Note note) {
        long id;
        try {
            id = store.restore(note);
        } catch (RuntimeException e) {
            Dialogs.error(window, "Cannot restore the note", e.getMessage());
            return;
        }
        refresh();
        select(id);
    }

    private void select(long id) {
        for (var note : notes) {
            if (note.id() == id) {
                list.getSelectionModel().select(note);
                return;
            }
        }
    }

    private void fixEnglish() {
        var text = body.getText().strip();
        if (text.isEmpty()) {
            return;
        }
        fixButton.setDisable(true);
        say("Correcting…");
        long id = current;
        var thread = new Thread(() -> {
            try {
                var result = corrector.correct(text);
                Platform.runLater(() -> {
                    fixButton.setDisable(false);
                    say("");
                    if (id >= 0) {
                        Dialogs.attempt(window, "Cannot save the correction", () -> store.corrected(id, result));
                    }
                    fixed.setText(result);
                    show(fixBox);
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    fixButton.setDisable(false);
                    say("Correction failed: " + e.getMessage());
                });
            }
        }, "note-corrector");
        thread.setDaemon(true);
        thread.start();
    }

    private void setFilter(String id) {
        filterId = id;
        filter = switch (id) {
            case "inbox" -> store::inbox;
            case "pinned" -> store::pinned;
            case "all" -> () -> store.recent(500);
            default -> () -> store.byTag(id.substring(1));
        };
    }

    /** Reloads the list and the chips for the current filter and search text. */
    private void refresh() {
        rebuildChips();
        var query = search.getText().strip();
        var found = query.isEmpty() ? filter.get() : store.search(query);
        if (!query.isEmpty()) {
            var allowed = filter.get().stream().map(Note::id).toList();
            found = found.stream().filter(n -> allowed.contains(n.id())).toList();
        }
        notes.setAll(found);
        if (current >= 0) {
            var model = list.getSelectionModel();
            for (var note : notes) {
                if (note.id() == current) {
                    model.select(note);
                    break;
                }
            }
        }
    }

    private void rebuildChips() {
        chips.getChildren().clear();
        chips.getChildren().add(chip("All", "all"));
        chips.getChildren().add(chip("Inbox", "inbox"));
        chips.getChildren().add(chip("Pinned", "pinned"));
        for (var tag : store.allTags()) {
            chips.getChildren().add(chip("#" + tag, "#" + tag));
        }
    }

    private ToggleButton chip(String label, String id) {
        var chip = new ToggleButton(label);
        chip.getStyleClass().add("notes-chip");
        chip.setToggleGroup(chipGroup);
        chip.setSelected(id.equals(filterId));
        chip.setOnAction(e -> {
            setFilter(chip.isSelected() ? id : "all");
            refresh();
        });
        return chip;
    }

    private static String relative(Instant when) {
        var age = Duration.between(when, Instant.now());
        if (age.toMinutes() < 1) {
            return "now";
        } else if (age.toHours() < 1) {
            return age.toMinutes() + " min ago";
        } else if (age.toDays() < 1) {
            return age.toHours() + " h ago";
        } else if (age.toDays() < 30) {
            return age.toDays() + " d ago";
        }
        return java.time.LocalDate.ofInstant(when, java.time.ZoneId.systemDefault()).toString();
    }

    /** Project dropdown cell: the folder name, or a placeholder for no project. */
    private static final class ProjectCell extends ListCell<String> {
        @Override
        protected void updateItem(String path, boolean empty) {
            super.updateItem(path, empty);
            if (empty) {
                setText(null);
            } else {
                setText(path == null ? "No project" : Path.of(path).getFileName().toString());
            }
        }
    }

    /** Cell with title, one-line preview and a meta line of tags, project and age. */
    private static final class NoteCell extends ListCell<Note> {
        private final Label pin = new Label("★");
        private final Label title = new Label();
        private final Label preview = new Label();
        private final Label tags = new Label();
        private final Label meta = new Label();
        private final HBox titleRow = new HBox(6, pin, title);
        private final HBox metaRow = new HBox(8, tags, meta);
        private final VBox box = new VBox(2, titleRow, preview, metaRow);

        NoteCell() {
            pin.getStyleClass().add("note-pin");
            title.getStyleClass().add("note-title");
            preview.getStyleClass().add("note-preview");
            tags.getStyleClass().add("note-tag");
            meta.getStyleClass().add("note-meta");
            box.setPadding(new Insets(2, 0, 2, 0));
        }

        @Override
        protected void updateItem(Note note, boolean empty) {
            super.updateItem(note, empty);
            if (empty || note == null) {
                setGraphic(null);
                return;
            }
            pin.setVisible(note.pinned());
            pin.setManaged(note.pinned());
            title.setText(note.displayTitle().isEmpty() ? "(empty)" : note.displayTitle());
            preview.setText(note.preview());
            tags.setText(note.tagList().stream().map(t -> "#" + t).reduce((a, b) -> a + " " + b).orElse(""));
            var parts = new java.util.ArrayList<String>();
            if (note.projectPath() != null) {
                parts.add(Path.of(note.projectPath()).getFileName().toString());
            }
            if (note.inbox()) {
                parts.add("inbox");
            }
            parts.add(relative(note.updatedAt()));
            meta.setText(String.join(" · ", parts));
            setGraphic(box);
        }
    }
}
