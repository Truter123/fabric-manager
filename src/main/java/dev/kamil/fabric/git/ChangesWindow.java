package dev.kamil.fabric.git;

import dev.kamil.fabric.Dialogs;
import dev.kamil.fabric.session.HistoryWindow;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Non-modal window listing the uncommitted changes of a repository: diff per file, commit all, discard a file. */
public final class ChangesWindow {

    private static final int MAX_DIFF_LINES = 5000;
    private static final Map<Path, ChangesWindow> OPEN = new HashMap<>();

    private final Path directory;
    private volatile GitRepo repo;
    private Scene scene;
    private final Stage window = new Stage();
    private final ListView<GitRepo.Change> list = new ListView<>();
    private final ListView<DiffView.Row> diff = new ListView<>();
    private final Label diffPath = new Label();
    private final TextField message = new TextField();
    private final Button commit = new Button("Commit all");
    private final Button discard = new Button("Discard file");
    private final Button refresh = new Button("Refresh");
    /** True once the repository is found; Commit stays off until then. */
    private final BooleanProperty loaded = new SimpleBooleanProperty();
    /** True while a commit runs, so a second Enter or click cannot commit twice. */
    private final BooleanProperty committing = new SimpleBooleanProperty();
    private final AtomicInteger generation = new AtomicInteger();

    /** Opens the window for {@code directory}, or brings the existing one to front. */
    public static void open(Window owner, Path directory) {
        var key = directory.toAbsolutePath().normalize();
        var existing = OPEN.get(key);
        if (existing != null) {
            existing.window.show();
            existing.window.setIconified(false);
            existing.window.toFront();
            existing.window.requestFocus();
            return;
        }
        var created = new ChangesWindow(owner, key);
        OPEN.put(key, created);
        created.window.setOnHidden(e -> OPEN.remove(key));
        created.show();
    }

    /** The open window's stage for {@code directory}, or null; a seam for tests. */
    static Stage openStage(Path directory) {
        var open = OPEN.get(directory.toAbsolutePath().normalize());
        return open == null ? null : open.window;
    }

    public ChangesWindow(Window owner, Path directory) {
        this.directory = directory;
        window.initOwner(owner);
        window.setTitle("Changes — " + directory.getFileName());
        refresh.setDisable(true);
        scene = new Scene(build(), 1000, 650);
        scene.getStylesheets().add(ChangesWindow.class.getResource("/app.css").toExternalForm());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ESCAPE), () -> Dialogs.closeLater(window));
        window.setScene(scene);
    }

    /** The window stage; for tests. */
    Stage stage() {
        return window;
    }

    public void show() {
        window.show();
        window.toFront();
        background(() -> {
            var found = GitRepo.of(directory).orElse(null);
            Platform.runLater(() -> {
                if (found == null) {
                    scene.setRoot(notARepository());
                    return;
                }
                repo = found;
                loaded.set(true);
                refresh.setDisable(false);
                reload();
            });
        });
    }

    private static BorderPane notARepository() {
        var root = new BorderPane(new Label("Not a git repository"));
        root.getStyleClass().addAll("root", "history");
        return root;
    }

    private BorderPane build() {
        list.setCellFactory(v -> new ChangeCell());
        list.setPlaceholder(new Label("No changes"));
        list.getSelectionModel().selectedItemProperty().addListener((o, a, change) -> showDiff(change));
        diffPath.setStyle("-fx-font-weight: bold;");
        var diffBar = new HBox(diffPath);
        diffBar.getStyleClass().add("diff-bar");
        diff.getStyleClass().add("diff-view");
        diff.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");
        diff.setCellFactory(v -> new DiffCell());
        diff.setPlaceholder(new Label("No diff"));
        var split = new SplitPane(list, new BorderPane(diff, diffBar, null, null, null));
        split.setDividerPositions(0.3);

        message.setPromptText("Commit message");
        HBox.setHgrow(message, Priority.ALWAYS);
        commit.getStyleClass().add("primary");
        commit.disableProperty().bind(loaded.not().or(committing)
                .or(Bindings.createBooleanBinding(() -> message.getText().isBlank(), message.textProperty())));
        commit.setOnAction(e -> commitAll());
        message.setOnAction(e -> {
            if (!commit.isDisabled()) {
                commitAll();
            }
        });
        discard.setOnAction(e -> discardSelected());
        refresh.setOnAction(e -> reload());
        discard.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        var apart = new Region();
        apart.setMinWidth(32);
        var bar = new HBox(8, message, commit, apart, discard, refresh);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6));

        var footer = new VBox(bar, Dialogs.keyHint("Enter (in the message) commit   Esc close"));
        var root = new BorderPane(split, null, null, footer, null);
        root.getStyleClass().addAll("root", "history");
        return root;
    }

    private void reload() {
        var gen = generation.incrementAndGet();
        var selected = list.getSelectionModel().getSelectedItem();
        background(() -> {
            try {
                var changes = repo.changes();
                Platform.runLater(() -> {
                    if (gen != generation.get()) {
                        return;
                    }
                    list.getItems().setAll(changes);
                    var keep = selected == null ? null
                            : changes.stream().filter(c -> c.path().equals(selected.path())).findFirst().orElse(null);
                    if (keep != null) {
                        list.getSelectionModel().select(keep);
                    } else if (!changes.isEmpty()) {
                        list.getSelectionModel().selectFirst();
                    } else {
                        showDiff(null);
                    }
                });
            } catch (GitRepo.GitException e) {
                Platform.runLater(() -> Dialogs.error(window, "Cannot read changes", e.getMessage()));
            }
        });
    }

    private void showDiff(GitRepo.Change change) {
        diff.getItems().clear();
        diffPath.setText(change == null ? "" : change.path());
        if (change == null) {
            return;
        }
        background(() -> {
            DiffView.Parsed parsed;
            try {
                parsed = DiffView.parse(repo.diff(change.path()), MAX_DIFF_LINES);
            } catch (GitRepo.GitException e) {
                parsed = new DiffView.Parsed(
                        List.of(new DiffView.Row(0, 0, String.valueOf(e.getMessage()), HistoryWindow.DiffKind.HEADER)), 0, 0, false);
            }
            var result = parsed;
            Platform.runLater(() -> {
                if (list.getSelectionModel().getSelectedItem() == change) {
                    diff.getItems().setAll(result.rows());
                    diff.setPlaceholder(new Label(result.binary() ? "Binary file" : "No diff"));
                }
            });
        });
    }

    private void commitAll() {
        var text = message.getText();
        committing.set(true);
        background(() -> {
            try {
                repo.commitAll(text);
                Platform.runLater(() -> {
                    committing.set(false);
                    message.clear();
                    reload();
                });
            } catch (GitRepo.GitException e) {
                Platform.runLater(() -> {
                    committing.set(false);
                    Dialogs.error(window, "Cannot commit", e.getMessage());
                });
            }
        });
    }

    private void discardSelected() {
        var change = list.getSelectionModel().getSelectedItem();
        if (change == null || !Dialogs.confirm(window, "Discard " + change.path() + "?",
                "The changes in this file are lost.", "Discard")) {
            return;
        }
        background(() -> {
            try {
                repo.discard(change.path());
                Platform.runLater(this::reload);
            } catch (GitRepo.GitException e) {
                Platform.runLater(() -> Dialogs.error(window, "Cannot discard " + change.path(), e.getMessage()));
            }
        });
    }

    private static void background(Runnable task) {
        Thread.ofPlatform().daemon().name("changes-git").start(task);
    }

    private static final class ChangeCell extends ListCell<GitRepo.Change> {
        @Override
        protected void updateItem(GitRepo.Change change, boolean empty) {
            super.updateItem(change, empty);
            getStyleClass().removeIf(s -> s.startsWith("status-"));
            if (empty || change == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            var letter = change.untracked() ? "U" : change.status().strip().substring(0, 1);
            var mark = new Label(letter);
            mark.getStyleClass().addAll("file-status", "status-" + (change.untracked() ? "a" : letter.toLowerCase(Locale.ROOT)));
            mark.setMinWidth(14);
            setText(null);
            setGraphic(new HBox(6, mark, new Label(change.path())));
        }
    }

    private static final class DiffCell extends ListCell<DiffView.Row> {
        @Override
        protected void updateItem(DiffView.Row row, boolean empty) {
            super.updateItem(row, empty);
            getStyleClass().removeIf(s -> s.startsWith("diff-"));
            if (empty || row == null) {
                setText(null);
                return;
            }
            setText(row.text());
            getStyleClass().add("diff-" + row.kind().name().toLowerCase(Locale.ROOT));
        }
    }
}
