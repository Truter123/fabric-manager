package dev.kamil.fabric.english;

import dev.kamil.fabric.Dialogs;
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
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * The sentences of everyday.md as a list of cards, newest first: voice, takes, edit and soft delete; one pane of the
 * English window. Removed sentences move to removed.md on save.
 */
final class SentencesPane extends BorderPane {

    private static final DateTimeFormatter ADDED = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);
    private static final int TAKES_SHOWN = 5;

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

        var add = button("Add", "Add (Ctrl+N)", this::add);
        var save = button("Save", "Save (Ctrl+S)", this::save);
        var reload = button("⟳", "Reload (F5)", this::reload);
        var bar = new HBox(8, search, count, add, save, reload);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8));

        removedPane.setAnimated(false);
        removedPane.setExpanded(false);
        removedPane.getStyleClass().add("sentence-removed-pane");
        var content = new VBox(10, cards, removedPane);
        content.setPadding(new Insets(8));
        scroll.setContent(content);
        scroll.setFitToWidth(true);

        var statusBar = new HBox(status);
        statusBar.setPadding(new Insets(6, 8, 6, 8));
        setCenter(scroll);
        setTop(bar);
        setBottom(statusBar);
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
