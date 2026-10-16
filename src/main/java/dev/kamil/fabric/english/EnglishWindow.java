package dev.kamil.fabric.english;

import dev.kamil.fabric.Dialogs;
import dev.kamil.fabric.terminal.TerminalSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.util.Locale;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;
import java.util.function.Function;

/** Non-modal window to list, search, add, edit, delete and save the sentences of everyday.md. */
public final class EnglishWindow {

    private static final Path FILE = Path.of("/home/kamil/Documents/Tools/english/everyday.md");
    private static EnglishWindow instance;

    private final Stage window = new Stage();
    private final ObservableList<Sentence> backing = FXCollections.observableArrayList();
    private final FilteredList<Sentence> filtered = new FilteredList<>(backing);
    private final TableView<Sentence> table = new TableView<>(filtered);
    private final TextField search = new TextField();
    private final Label count = new Label();
    private final Label status = new Label();
    private int changes;
    private FileTime loadedMtime;

    /** Opens the window, or brings the existing one to front. */
    public static void open(Window owner) {
        if (instance != null) {
            instance.window.show();
            instance.window.setIconified(false);
            instance.window.toFront();
            instance.window.requestFocus();
            return;
        }
        var created = new EnglishWindow(owner);
        instance = created;
        created.window.setOnHidden(e -> instance = null);
        created.window.show();
        created.search.requestFocus();
        created.load();
    }

    private EnglishWindow(Window owner) {
        window.initOwner(owner);
        updateTitle();
        var scene = new Scene(build(), 1400, 800);
        scene.getStylesheets().add(EnglishWindow.class.getResource("/app.css").toExternalForm());
        var acc = scene.getAccelerators();
        acc.put(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN), this::add);
        acc.put(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN), this::save);
        acc.put(new KeyCodeCombination(KeyCode.F5), this::reload);
        window.setOnCloseRequest(e -> {
            if (!discardOk()) {
                e.consume();
            }
        });
        window.setScene(scene);
    }

    private BorderPane build() {
        search.setPromptText("Search…");
        search.textProperty().addListener((o, a, text) -> {
            var q = text.strip().toLowerCase(Locale.ROOT);
            filtered.setPredicate(s -> q.isEmpty() || haystack(s).contains(q));
            updateCount();
        });
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                search.clear();
                e.consume();
            }
        });
        HBox.setHgrow(search, Priority.ALWAYS);
        count.getStyleClass().add("history-meta");
        status.getStyleClass().add("history-meta");

        var add = button("Add", "Add (Ctrl+N)", this::add);
        var edit = button("Edit", "Edit (Enter, double-click)", this::edit);
        var delete = button("Delete", "Delete (Del)", this::delete);
        var save = button("Save", "Save (Ctrl+S)", this::save);
        var reload = button("⟳", "Reload (F5)", this::reload);
        var bar = new HBox(8, search, count, add, edit, delete, save, reload);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8));

        var mono = "-fx-font-family: '" + fontFamily() + "'; -fx-font-size: 13px;";
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No sentences"));
        table.getColumns().add(column("Date", 0.07, Sentence::date, null));
        table.getColumns().add(column("English", 0.19, Sentence::english, null));
        table.getColumns().add(column("PL", 0.19, Sentence::pl, null));
        table.getColumns().add(column("Say", 0.21, Sentence::say, mono));
        table.getColumns().add(column("Watch", 0.34, Sentence::watchPlain, null));
        table.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                edit();
            }
        });
        table.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE) {
                delete();
            } else if (e.getCode() == KeyCode.ENTER) {
                edit();
            }
        });

        var statusBar = new HBox(status);
        statusBar.setPadding(new Insets(6, 8, 6, 8));
        var root = new BorderPane(table);
        root.setTop(bar);
        root.setBottom(statusBar);
        return root;
    }

    private static Button button(String text, String tip, Runnable action) {
        var b = new Button(text);
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> action.run());
        return b;
    }

    private TableColumn<Sentence, String> column(String title, double share, Function<Sentence, String> value,
            String style) {
        var col = new TableColumn<Sentence, String>(title);
        col.setCellValueFactory(c -> new ReadOnlyStringWrapper(value.apply(c.getValue())));
        col.setSortable(false);
        col.setCellFactory(c -> new WrapCell(style));
        col.prefWidthProperty().bind(table.widthProperty().multiply(share));
        return col;
    }

    /** Cell whose text wraps at the column width. */
    private static final class WrapCell extends TableCell<Sentence, String> {
        private final Text text = new Text();

        WrapCell(String style) {
            if (style != null) {
                text.setStyle(style);
            }
            text.getStyleClass().add("text");
            text.wrappingWidthProperty().bind(widthProperty().subtract(14));
            setGraphic(text);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            text.setText(empty || item == null ? "" : item);
            text.setFill(isSelected() ? javafx.scene.paint.Color.WHITE : javafx.scene.paint.Color.web("#d4d4d4"));
            setGraphic(empty ? null : text);
        }

        @Override
        public void updateSelected(boolean selected) {
            super.updateSelected(selected);
            text.setFill(selected ? javafx.scene.paint.Color.WHITE : javafx.scene.paint.Color.web("#d4d4d4"));
        }
    }

    private static String haystack(Sentence s) {
        return String.join("\n", s.date(), s.english(), s.pl(), s.say(), s.watchPlain()).toLowerCase(Locale.ROOT);
    }

    private static String fontFamily() {
        try {
            return new TerminalSettings().getTerminalFont().getFamily();
        } catch (RuntimeException | LinkageError e) {
            return "monospace";
        }
    }

    // ---- state ----

    private void updateCount() {
        count.setText(filtered.size() + " of " + backing.size());
    }

    private void updateTitle() {
        window.setTitle("English sentences" + (changes > 0 ? " *" : ""));
    }

    private void changed(String what) {
        changes++;
        updateTitle();
        updateCount();
        status.setText(what + " — " + unsaved());
    }

    private String unsaved() {
        return changes + " unsaved change" + (changes == 1 ? "" : "s");
    }

    private FileTime mtime() {
        try {
            return Files.getLastModifiedTime(FILE);
        } catch (IOException e) {
            return null;
        }
    }

    private void load() {
        backing.clear();
        changes = 0;
        loadedMtime = null;
        if (!Files.exists(FILE)) {
            Dialogs.error(window, "Cannot read sentences", FILE + " does not exist. Save will create it.");
            status.setText("File missing — Save creates it");
        } else {
            try {
                backing.setAll(SentenceFile.load(FILE));
                loadedMtime = mtime();
                status.setText("Loaded " + backing.size() + " sentences");
            } catch (IOException e) {
                Dialogs.error(window, "Cannot read sentences", e.getMessage());
                status.setText("Load failed");
            }
        }
        updateTitle();
        updateCount();
    }

    private void reload() {
        if (discardOk()) {
            load();
        }
    }

    /** True when there is nothing unsaved or the user chose to save or discard. */
    private boolean discardOk() {
        if (changes == 0) {
            return true;
        }
        var saveType = new ButtonType("Save", ButtonBar.ButtonData.YES);
        var discard = new ButtonType("Discard", ButtonBar.ButtonData.NO);
        var alert = new Alert(Alert.AlertType.NONE, unsaved() + ".", saveType, discard, ButtonType.CANCEL);
        alert.setTitle("FabricManager");
        alert.setHeaderText("Unsaved changes");
        Dialogs.style(alert.getDialogPane());
        alert.initOwner(window);
        var choice = alert.showAndWait().orElse(ButtonType.CANCEL);
        if (choice == saveType) {
            return save();
        }
        return choice == discard;
    }

    private boolean save() {
        if (Files.exists(FILE) && loadedMtime != null && !loadedMtime.equals(mtime())) {
            var reload = new ButtonType("Reload (lose edits)", ButtonBar.ButtonData.NO);
            var overwrite = new ButtonType("Overwrite", ButtonBar.ButtonData.YES);
            var alert = new Alert(Alert.AlertType.NONE, "The file was modified since it was loaded.", reload,
                    overwrite, ButtonType.CANCEL);
            alert.setTitle("FabricManager");
            alert.setHeaderText("File changed on disk");
            Dialogs.style(alert.getDialogPane());
            alert.initOwner(window);
            var choice = alert.showAndWait().orElse(ButtonType.CANCEL);
            if (choice == reload) {
                load();
                return false;
            }
            if (choice != overwrite) {
                return false;
            }
        }
        try {
            SentenceFile.save(FILE, backing);
        } catch (IOException e) {
            Dialogs.error(window, "Cannot save sentences", e.getMessage());
            status.setText("Save failed");
            return false;
        }
        loadedMtime = mtime();
        changes = 0;
        updateTitle();
        status.setText("Saved " + backing.size() + " sentences");
        return true;
    }

    // ---- editing ----

    private void add() {
        edit(null).ifPresent(s -> {
            backing.add(s);
            table.getSelectionModel().select(s);
            table.scrollTo(s);
            changed("Added");
        });
    }

    private void edit() {
        var selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            status.setText("Select a sentence first");
            return;
        }
        edit(selected).ifPresent(s -> {
            int i = backing.indexOf(selected);
            if (i >= 0) {
                backing.set(i, s);
                table.getSelectionModel().select(s);
                changed("Edited");
            }
        });
    }

    private void delete() {
        var selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            status.setText("Select a sentence first");
            return;
        }
        if (Dialogs.confirm(window, "Delete this sentence?", selected.english(), "Delete")) {
            backing.remove(selected);
            changed("Deleted");
        }
    }

    private java.util.Optional<Sentence> edit(Sentence base) {
        var dialog = new Dialog<Sentence>();
        dialog.setTitle(base == null ? "Add sentence" : "Edit sentence");
        dialog.initOwner(window);
        Dialogs.style(dialog.getDialogPane());
        var date = new DatePicker(base == null ? LocalDate.now() : parseDate(base.date()));
        var english = new TextField(base == null ? "" : base.english());
        var pl = new TextField(base == null ? "" : base.pl());
        var say = new TextField(base == null ? "" : base.say());
        var watch = new TextArea(base == null ? "" : base.watch());
        watch.setWrapText(true);
        watch.setPrefRowCount(5);
        var mono = "-fx-font-family: '" + fontFamily() + "';";
        say.setStyle(mono);
        var grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        String[] names = {"Date", "English", "PL", "Say", "Watch"};
        javafx.scene.Node[] fields = {date, english, pl, say, watch};
        for (int i = 0; i < names.length; i++) {
            grid.add(new Label(names[i]), 0, i);
            grid.add(fields[i], 1, i);
            GridPane.setHgrow(fields[i], Priority.ALWAYS);
        }
        var box = new VBox(grid);
        box.setPrefWidth(760);
        dialog.getDialogPane().setContent(box);
        var ok = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        dialog.setResultConverter(b -> b == ok
                ? new Sentence(
                        (date.getValue() == null ? LocalDate.now() : date.getValue()).toString(),
                        english.getText().strip(), pl.getText().strip(),
                        say.getText().strip().replace("`", ""), watch.getText().strip().replaceAll("\\s*\\R\\s*", " "))
                : null);
        javafx.application.Platform.runLater(english::requestFocus);
        return dialog.showAndWait();
    }

    private static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text);
        } catch (RuntimeException e) {
            return LocalDate.now();
        }
    }
}
