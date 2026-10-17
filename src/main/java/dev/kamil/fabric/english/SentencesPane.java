package dev.kamil.fabric.english;

import dev.kamil.fabric.Dialogs;
import dev.kamil.fabric.settings.AppSettings;
import dev.kamil.fabric.terminal.TerminalSettings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Window;

/**
 * The sentences of everyday.md as a list of cards, newest first: voice, takes, edit and soft delete; one pane of the
 * English window. Removed sentences move to removed.md on save.
 */
final class SentencesPane extends BorderPane {

    private static final DateTimeFormatter ADDED = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);
    private static final int TAKES_SHOWN = 5;
    static final String VIEW_KEY = "english.sentences_view";

    private final Window owner;
    private final Runnable onChange;
    /** The sentences that stay in everyday.md. */
    private final List<Sentence> backing = new ArrayList<>();
    /** Every removed sentence: removed.md plus the ones removed since the last load or save. */
    private final List<Sentence> removed = new ArrayList<>();
    /** Removed since the last load or save: they still show as a one-line row with Undo. */
    private final List<Sentence> justRemoved = new ArrayList<>();
    private final VBox cards = new VBox(10);
    private final TitledPane removedPane = new TitledPane();
    private final ScrollPane scroll = new ScrollPane();
    private final TableView<Sentence> table = new TableView<>();
    private final VBox tableBox = new VBox(8);
    private final VBox cardsContent = new VBox(10);
    private final ToggleButton cardsToggle = new ToggleButton("Cards");
    private final ToggleButton tableToggle = new ToggleButton("Table");
    private final Button editButton = new Button("Edit");
    private boolean tableView;
    private boolean rebuilding;
    private Sentence lastSelected;
    private TableColumn<Sentence, ?> lastColumn;
    private final TextField search = new TextField();
    private final Label count = new Label();
    private final Label status = new Label();
    private final RecordingHistory history = RecordingHistory.standard();
    private final Recorder recorder = new Recorder();
    private final String mono = "-fx-font-family: '" + fontFamily() + "'; -fx-font-size: 13px;";
    private Card recordingCard;
    private int visible;
    private int changes;
    private FileTime loadedMtime;

    /** {@code onChange} runs when the unsaved-change count changes. */
    SentencesPane(Window owner, Runnable onChange) {
        this.owner = owner;
        this.onChange = onChange;
        build();
    }

    /** The sentences in memory that are not removed, saved or not. */
    List<Sentence> sentences() {
        return List.copyOf(backing);
    }

    int changes() {
        return changes;
    }

    /** True while a table cell is being edited, so Esc only cancels that edit. */
    boolean isEditing() {
        return tableView && table.getEditingCell() != null;
    }

    boolean isTableView() {
        return tableView;
    }

    /** Switches between the card list and the table. */
    void toggleView() {
        (tableView ? cardsToggle : tableToggle).setSelected(true);
    }

    /** Opens the full edit dialog for the selected table row. */
    void editSelected() {
        if (tableView && lastSelected != null && backing.contains(lastSelected)) {
            editCard(lastSelected);
        }
    }

    void focusSearch() {
        search.requestFocus();
    }

    /** True when Esc should only clear the search field (it has text and is the key target or focused). */
    boolean escapeClearsSearch(javafx.scene.input.KeyEvent e) {
        return !search.getText().isEmpty() && (e.getTarget() == search || search.isFocused());
    }

    /** Stops playback and saves a running recording; called before leaving the pane. */
    void stopAll() {
        recorder.stopPlayback();
        stopRecording();
    }

    private void build() {
        search.setPromptText("Search…");
        search.textProperty().addListener((o, a, text) -> rebuild());
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                search.clear();
                e.consume();
            }
        });
        HBox.setHgrow(search, Priority.ALWAYS);
        count.getStyleClass().add("history-meta");
        status.getStyleClass().add("history-meta");

        var group = new ToggleGroup();
        cardsToggle.setToggleGroup(group);
        tableToggle.setToggleGroup(group);
        cardsToggle.setFocusTraversable(false);
        tableToggle.setFocusTraversable(false);
        cardsToggle.setTooltip(new Tooltip("Cards (Ctrl+T toggles)"));
        tableToggle.setTooltip(new Tooltip("Table (Ctrl+T toggles)"));
        tableView = "table".equals(AppSettings.shared().getString(VIEW_KEY, "cards"));
        (tableView ? tableToggle : cardsToggle).setSelected(true);
        group.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null) {
                was.setSelected(true);
                return;
            }
            tableView = now == tableToggle;
            AppSettings.shared().setString(VIEW_KEY, tableView ? "table" : "cards");
            applyView();
        });
        var add = button("Add", "Add (Ctrl+N)", this::add);
        editButton.setTooltip(new Tooltip("Edit the selected row (Ctrl+E)"));
        editButton.setOnAction(e -> editSelected());
        var save = button("Save", "Save (Ctrl+S)", this::save);
        var reload = button("⟳", "Reload (F5)", this::reload);
        var bar = new HBox(8, search, count, cardsToggle, tableToggle, add, editButton, save, reload);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8));

        removedPane.setAnimated(false);
        removedPane.setExpanded(false);
        removedPane.getStyleClass().add("sentence-removed-pane");
        cardsContent.getChildren().addAll(cards, removedPane);
        cardsContent.setPadding(new Insets(8));
        scroll.setContent(cardsContent);
        scroll.setFitToWidth(true);
        buildTable();
        tableBox.setPadding(new Insets(8));

        var statusBar = new HBox(status);
        statusBar.setPadding(new Insets(6, 8, 6, 8));
        setTop(bar);
        setBottom(statusBar);
        applyView();
    }

    /** Shows the card list or the table; the Removed section moves under the active one. */
    private void applyView() {
        removedPane.setMaxWidth(Double.MAX_VALUE);
        cardsContent.getChildren().remove(removedPane);
        tableBox.getChildren().remove(removedPane);
        if (tableView) {
            tableBox.getChildren().add(removedPane);
            setCenter(tableBox);
        } else {
            cardsContent.getChildren().add(removedPane);
            setCenter(scroll);
        }
        editButton.setVisible(tableView);
        editButton.setManaged(tableView);
        rebuild();
    }

    // ---- table ----

    private void buildTable() {
        var tmono = mono;
        table.setEditable(true);
        table.getSelectionModel().setCellSelectionEnabled(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No sentences"));
        table.getColumns().add(column("Date", 0.08, Sentence::date, null, Function.identity(), true));
        table.getColumns().add(column("English", 0.18, Sentence::english, null, Function.identity(), true));
        table.getColumns().add(column("PL", 0.18, Sentence::pl, null, Function.identity(), true));
        table.getColumns().add(column("Say", 0.20, Sentence::say, tmono, Function.identity(), true));
        table.getColumns().add(column("Rec", 0.05, s -> {
            int n = takes(s).size();
            return n == 0 ? "" : Integer.toString(n);
        }, null, Function.identity(), false));
        table.getColumns().add(column("Watch", 0.31, Sentence::watch, null, t -> t.replace("**", ""), true));
        table.getSelectionModel().getSelectedCells().addListener((ListChangeListener<javafx.scene.control.TablePosition>) c -> {
            if (rebuilding) {
                return;
            }
            var cells = table.getSelectionModel().getSelectedCells();
            if (cells.isEmpty()) {
                lastSelected = null;
            } else {
                int row = cells.get(0).getRow();
                lastSelected = row >= 0 && row < table.getItems().size() ? table.getItems().get(row) : null;
                lastColumn = cells.get(0).getTableColumn();
            }
        });
        table.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !isEditing()) {
                startEdit();
            }
        });
        table.setOnKeyPressed(e -> {
            if (isEditing()) {
                return;
            }
            if (e.getCode() == KeyCode.DELETE && lastSelected != null) {
                remove(lastSelected);
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.F2) {
                startEdit();
                e.consume();
            }
        });
        VBox.setVgrow(table, Priority.ALWAYS);
        tableBox.getChildren().add(table);
    }

    private void startEdit() {
        int row = table.getSelectionModel().getSelectedIndex();
        var col = table.getFocusModel().getFocusedCell().getTableColumn();
        if (col == null) {
            col = lastColumn != null ? lastColumn : table.getColumns().get(1);
        }
        if (row >= 0 && col.isEditable()) {
            table.edit(row, col);
        }
    }

    private TableColumn<Sentence, String> column(String title, double share, Function<Sentence, String> value,
            String style, Function<String, String> display, boolean editable) {
        var col = new TableColumn<Sentence, String>(title);
        col.setCellValueFactory(c -> new ReadOnlyStringWrapper(value.apply(c.getValue())));
        col.setSortable(false);
        col.setEditable(editable);
        col.setCellFactory(c -> new EditCell(style, display));
        col.prefWidthProperty().bind(table.widthProperty().multiply(share));
        col.setOnEditCommit(e -> applyEdit(e.getRowValue(), title, e.getNewValue()));
        return col;
    }

    /** Builds the edited record from one cell's new text; a bad date keeps the old value. */
    private void applyEdit(Sentence old, String field, String raw) {
        var text = raw == null ? "" : raw.strip().replaceAll("\\s*\\R\\s*", " ");
        Sentence edited;
        switch (field) {
            case "Date" -> {
                try {
                    text = LocalDate.parse(text).toString();
                } catch (RuntimeException e) {
                    status.setText("Date must be yyyy-MM-dd");
                    table.refresh();
                    return;
                }
                edited = new Sentence(text, old.english(), old.pl(), old.say(), old.watch());
            }
            case "English" -> edited = new Sentence(old.date(), text, old.pl(), old.say(), old.watch());
            case "PL" -> edited = new Sentence(old.date(), old.english(), text, old.say(), old.watch());
            case "Say" -> edited = new Sentence(old.date(), old.english(), old.pl(), text.replace("`", ""),
                    old.watch());
            case "Watch" -> edited = new Sentence(old.date(), old.english(), old.pl(), old.say(), text);
            default -> {
                return;
            }
        }
        if (edited.equals(old)) {
            return;
        }
        if (edited.english().isEmpty()) {
            status.setText("English cannot be empty");
            table.refresh();
            return;
        }
        int i = backing.indexOf(old);
        if (i >= 0) {
            backing.set(i, edited);
            lastSelected = edited;
            changed("Edited");
        }
    }

    /** Cell whose text wraps at the column width and edits in a TextField on the raw value. */
    private static final class EditCell extends TableCell<Sentence, String> {
        private final Text text = new Text();
        private final Function<String, String> display;
        private final String style;

        EditCell(String style, Function<String, String> display) {
            this.style = style;
            this.display = display;
            if (style != null) {
                text.setStyle(style);
            }
            text.getStyleClass().add("text");
            text.wrappingWidthProperty().bind(widthProperty().subtract(14));
        }

        private void paint() {
            text.setText(getItem() == null ? "" : display.apply(getItem()));
            text.setFill(isSelected() ? javafx.scene.paint.Color.WHITE : javafx.scene.paint.Color.web("#d4d4d4"));
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            paint();
            if (!isEditing()) {
                setGraphic(text);
            }
        }

        @Override
        public void updateSelected(boolean selected) {
            super.updateSelected(selected);
            paint();
        }

        @Override
        public void startEdit() {
            super.startEdit();
            if (!isEditing()) {
                return;
            }
            var field = new TextField(getItem());
            if (style != null) {
                field.setStyle(style);
            }
            field.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ENTER) {
                    commitEdit(field.getText());
                    e.consume();
                } else if (e.getCode() == KeyCode.ESCAPE) {
                    cancelEdit();
                    e.consume();
                }
            });
            setGraphic(field);
            javafx.application.Platform.runLater(() -> {
                field.requestFocus();
                field.selectAll();
            });
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            paint();
            setGraphic(text);
            if (getTableView() != null) {
                getTableView().requestFocus();
            }
        }

        @Override
        public void commitEdit(String value) {
            super.commitEdit(value);
            paint();
            setGraphic(text);
            if (getTableView() != null) {
                getTableView().requestFocus();
            }
        }
    }

    private static Button button(String text, String tip, Runnable action) {
        var b = new Button(text);
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> action.run());
        return b;
    }

    private static String haystack(Sentence s) {
        return String.join("\n", s.date(), s.english(), s.pl(), s.say(), s.watchPlain()).toLowerCase(Locale.ROOT);
    }

    static String fontFamily() {
        try {
            return new TerminalSettings().getTerminalFont().getFamily();
        } catch (RuntimeException | LinkageError e) {
            return "monospace";
        }
    }

    // ---- cards ----

    /** Rebuilds the card list: newest date first, then the later lines of the file first. */
    private void rebuild() {
        stopAll();
        var q = search.getText().strip().toLowerCase(Locale.ROOT);
        var rows = new ArrayList<>(backing);
        rows.addAll(justRemoved);
        Collections.reverse(rows);
        rows.sort(Comparator.comparing(Sentence::date).reversed());
        if (tableView) {
            rebuildTable(rows, q);
            rebuildRemoved();
            updateCount();
            return;
        }
        cards.getChildren().clear();
        visible = 0;
        int shown = 0;
        for (var s : rows) {
            if (!q.isEmpty() && !haystack(s).contains(q)) {
                continue;
            }
            shown++;
            if (justRemoved.contains(s)) {
                cards.getChildren().add(removedRow(s));
            } else {
                visible++;
                cards.getChildren().add(new Card(s).root);
            }
        }
        if (shown == 0) {
            var none = new Label("No sentences");
            none.getStyleClass().add("sentence-muted");
            cards.getChildren().add(none);
        }
        rebuildRemoved();
        updateCount();
    }

    private void rebuildTable(List<Sentence> rows, String q) {
        var shown = new ArrayList<Sentence>();
        for (var s : rows) {
            if (!justRemoved.contains(s) && (q.isEmpty() || haystack(s).contains(q))) {
                shown.add(s);
            }
        }
        visible = shown.size();
        rebuilding = true;
        try {
            table.getItems().setAll(shown);
            int i = lastSelected == null ? -1 : shown.indexOf(lastSelected);
            if (i >= 0) {
                var col = lastColumn != null ? lastColumn : table.getColumns().get(1);
                table.getSelectionModel().select(i, col);
            }
        } finally {
            rebuilding = false;
        }
        table.refresh();
    }

    private HBox removedRow(Sentence s) {
        var label = new Label(s.english() + " — Removed");
        var undo = new Button("Undo");
        undo.setOnAction(e -> restore(s));
        var row = new HBox(10, label, undo);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("sentence-removed");
        return row;
    }

    private void rebuildRemoved() {
        removedPane.setVisible(!removed.isEmpty());
        removedPane.setManaged(!removed.isEmpty());
        removedPane.setText("Removed (" + removed.size() + ")");
        var list = new VBox(6);
        for (var s : removed) {
            var label = new Label(s.english());
            label.setWrapText(true);
            var restore = new Button("Restore");
            restore.setOnAction(e -> restore(s));
            var spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            var row = new HBox(10, label, spacer, restore);
            row.setAlignment(Pos.CENTER_LEFT);
            list.getChildren().add(row);
        }
        removedPane.setContent(list);
    }

    private List<RecordingHistory.Attempt> takes(Sentence s) {
        try {
            return history.list(s.english());
        } catch (UncheckedIOException e) {
            return List.of();
        }
    }

    /** One sentence card: texts, voice and recording buttons, a message line and the newest takes. */
    private final class Card {
        final VBox root = new VBox(8);
        final Sentence sentence;
        final Button record = new Button("Record");
        final Label message = new Label();
        final VBox takes = new VBox(4);

        Card(Sentence s) {
            this.sentence = s;
            var english = new Label(s.english());
            english.getStyleClass().add("sentence-english");
            english.setWrapText(true);
            var texts = new VBox(4, english);
            if (!s.say().isBlank()) {
                var say = new Label(s.say());
                say.getStyleClass().add("sentence-muted");
                say.setStyle(mono);
                say.setWrapText(true);
                texts.getChildren().add(say);
            }
            if (!s.pl().isBlank()) {
                var pl = new Label(s.pl());
                pl.setWrapText(true);
                texts.getChildren().add(pl);
            }
            var added = addedText(s.date());
            if (added != null) {
                var date = new Label(added);
                date.getStyleClass().add("sentence-muted");
                texts.getChildren().add(date);
            }
            texts.getStyleClass().add("sentence-text");
            texts.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                    editCard(s);
                }
            });

            record.setOnAction(e -> toggleRecording(this));
            var buttons = new HBox(8, action("Listen", () -> listen(this, false)),
                    action("Slow", () -> listen(this, true)), record, action("Compare", () -> compare(this)),
                    action("Edit", () -> editCard(s)), action("Remove", () -> remove(s)));
            buttons.setAlignment(Pos.CENTER_LEFT);

            message.getStyleClass().add("sentence-muted");
            message.setWrapText(true);
            message(null);
            root.getStyleClass().add("sentence-card");
            root.getChildren().addAll(texts, buttons, message, takes);
            refreshTakes();
        }

        private Button action(String text, Runnable run) {
            var b = new Button(text);
            b.setOnAction(e -> run.run());
            return b;
        }

        void message(String text) {
            boolean has = text != null && !text.isBlank();
            message.setText(has ? text : "");
            message.setVisible(has);
            message.setManaged(has);
        }

        void refreshTakes() {
            takes.getChildren().clear();
            var all = takes(sentence);
            if (all.isEmpty()) {
                var none = new Label("No recordings yet.");
                none.getStyleClass().add("sentence-muted");
                takes.getChildren().add(none);
                return;
            }
            for (var a : all.subList(0, Math.min(TAKES_SHOWN, all.size()))) {
                var play = new Button("▶");
                play.setOnAction(e -> recorder.play(a.file(), null));
                var delete = new Button("Delete");
                delete.setOnAction(e -> deleteTake(this, a));
                var row = new HBox(8, new Label(RecordingHistory.label(a.at())), play, delete);
                row.setAlignment(Pos.CENTER_LEFT);
                takes.getChildren().add(row);
            }
        }
    }

    private static String addedText(String date) {
        try {
            return "Added " + ADDED.format(LocalDate.parse(date));
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- voice and takes ----

    private void listen(Card card, boolean slow) {
        card.message(null);
        Voice.async(card.sentence.english(), slow, wav -> recorder.play(wav, null), card::message);
    }

    private void compare(Card card) {
        var all = takes(card.sentence);
        if (all.isEmpty()) {
            card.message("Record a take first.");
            return;
        }
        card.message(null);
        var newest = all.get(0).file();
        Voice.async(card.sentence.english(), false, wav -> recorder.play(wav, () -> recorder.play(newest, null)),
                card::message);
    }

    private void toggleRecording(Card card) {
        if (recordingCard != null) {
            var was = recordingCard;
            stopRecording();
            if (was == card) {
                return;
            }
        }
        try {
            recorder.start();
            recordingCard = card;
            card.record.setText("Stop");
            card.message(null);
        } catch (IOException e) {
            card.message(e.getMessage());
        }
    }

    /** Stops the running capture and saves it as a new take of its card; the oldest takes beyond the limit go. */
    private void stopRecording() {
        var card = recordingCard;
        if (card == null) {
            return;
        }
        recordingCard = null;
        card.record.setText("Record");
        try {
            var saved = recorder.stop(history.newFile(card.sentence.english(), LocalDateTime.now()));
            if (saved != null) {
                history.prune(card.sentence.english(), RecordingHistory.KEEP);
            }
        } catch (IOException e) {
            card.message("Cannot save the recording: " + e.getMessage());
        }
        card.refreshTakes();
    }

    private void deleteTake(Card card, RecordingHistory.Attempt a) {
        if (!Dialogs.confirm(owner, "Delete this recording?", RecordingHistory.label(a.at()), "Delete")) {
            return;
        }
        try {
            recorder.stopPlayback();
            history.delete(a.file());
        } catch (IOException e) {
            card.message("Cannot delete the recording: " + e.getMessage());
        }
        card.refreshTakes();
    }

    // ---- state ----

    private void updateCount() {
        count.setText(visible + " of " + backing.size());
    }

    private void changed(String what) {
        changes++;
        onChange.run();
        status.setText(what + " — " + unsaved());
        rebuild();
    }

    private String unsaved() {
        return changes + " unsaved change" + (changes == 1 ? "" : "s");
    }

    private FileTime mtime() {
        try {
            return Files.getLastModifiedTime(EnglishWindow.file);
        } catch (IOException e) {
            return null;
        }
    }

    void load() {
        stopAll();
        backing.clear();
        removed.clear();
        justRemoved.clear();
        changes = 0;
        loadedMtime = null;
        if (!Files.exists(EnglishWindow.file)) {
            Dialogs.error(owner, "Cannot read sentences", EnglishWindow.file + " does not exist. Save will create it.");
            status.setText("File missing — Save creates it");
        } else {
            try {
                backing.addAll(SentenceFile.load(EnglishWindow.file));
                loadedMtime = mtime();
                if (Files.exists(EnglishWindow.removedFile())) {
                    removed.addAll(SentenceFile.load(EnglishWindow.removedFile()));
                }
                status.setText("Loaded " + backing.size() + " sentences");
            } catch (IOException e) {
                Dialogs.error(owner, "Cannot read sentences", e.getMessage());
                status.setText("Load failed");
            }
        }
        onChange.run();
        rebuild();
    }

    void reload() {
        if (discardOk()) {
            load();
        }
    }

    /** True when there is nothing unsaved or the user chose to save or discard. */
    boolean discardOk() {
        if (changes == 0) {
            return true;
        }
        var saveType = new ButtonType("Save", ButtonBar.ButtonData.YES);
        var discard = new ButtonType("Discard", ButtonBar.ButtonData.NO);
        var alert = new Alert(Alert.AlertType.NONE, unsaved() + ".", saveType, discard, ButtonType.CANCEL);
        alert.setTitle("FabricManager");
        alert.setHeaderText("Unsaved changes");
        Dialogs.style(alert.getDialogPane());
        alert.initOwner(owner);
        var choice = alert.showAndWait().orElse(ButtonType.CANCEL);
        if (choice == saveType) {
            return save();
        }
        return choice == discard;
    }

    boolean save() {
        if (Files.exists(EnglishWindow.file) && loadedMtime != null && !loadedMtime.equals(mtime())) {
            var reload = new ButtonType("Reload (lose edits)", ButtonBar.ButtonData.NO);
            var overwrite = new ButtonType("Overwrite", ButtonBar.ButtonData.YES);
            var alert = new Alert(Alert.AlertType.NONE, "The file was modified since it was loaded.", reload,
                    overwrite, ButtonType.CANCEL);
            alert.setTitle("FabricManager");
            alert.setHeaderText("File changed on disk");
            Dialogs.style(alert.getDialogPane());
            alert.initOwner(owner);
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
            SentenceFile.save(EnglishWindow.file, backing);
            if (!removed.isEmpty() || Files.exists(EnglishWindow.removedFile())) {
                SentenceFile.save(EnglishWindow.removedFile(), removed);
            }
        } catch (IOException e) {
            Dialogs.error(owner, "Cannot save sentences", e.getMessage());
            status.setText("Save failed");
            return false;
        }
        loadedMtime = mtime();
        changes = 0;
        justRemoved.clear();
        onChange.run();
        status.setText("Saved " + backing.size() + " sentences");
        rebuild();
        return true;
    }

    // ---- editing ----

    void add() {
        dialog(null).ifPresent(s -> {
            backing.add(s);
            changed("Added");
            scroll.setVvalue(0);
        });
    }

    private void editCard(Sentence selected) {
        dialog(selected).ifPresent(s -> {
            int i = backing.indexOf(selected);
            if (i >= 0) {
                backing.set(i, s);
                lastSelected = s;
                changed("Edited");
            }
        });
    }

    private void remove(Sentence s) {
        if (backing.remove(s)) {
            removed.add(s);
            justRemoved.add(s);
            changed("Removed");
        }
    }

    private void restore(Sentence s) {
        if (removed.remove(s)) {
            justRemoved.remove(s);
            backing.add(s);
            changed("Restored");
        }
    }

    private java.util.Optional<Sentence> dialog(Sentence base) {
        var dialog = new Dialog<Sentence>();
        dialog.setTitle(base == null ? "Add sentence" : "Edit sentence");
        dialog.initOwner(owner);
        Dialogs.style(dialog.getDialogPane());
        var date = new DatePicker(base == null ? LocalDate.now() : parseDate(base.date()));
        var english = new TextField(base == null ? "" : base.english());
        var pl = new TextField(base == null ? "" : base.pl());
        var say = new TextField(base == null ? "" : base.say());
        var watch = new TextArea(base == null ? "" : base.watch());
        watch.setWrapText(true);
        watch.setPrefRowCount(5);
        say.setStyle("-fx-font-family: '" + fontFamily() + "';");
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
