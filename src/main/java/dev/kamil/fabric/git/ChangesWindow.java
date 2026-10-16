package dev.kamil.fabric.git;

import dev.kamil.fabric.Dialogs;
import dev.kamil.fabric.session.HistoryWindow;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
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
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Non-modal window listing the uncommitted changes of a repository: diff per file, commit all, discard a file. */
public final class ChangesWindow {

    private static final int MAX_DIFF_LINES = 5000;

    private final GitRepo repo;
    private final Stage window = new Stage();
    private final ListView<GitRepo.Change> list = new ListView<>();
    private final ListView<DiffView.Row> diff = new ListView<>();
    private final Label diffPath = new Label();
    private final TextField message = new TextField();
    private final Button commit = new Button("Commit all");
    private final Button discard = new Button("Discard file");
    private final Button refresh = new Button("Refresh");
    private final AtomicInteger generation = new AtomicInteger();

    public ChangesWindow(Window owner, Path directory) {
        repo = GitRepo.of(directory).orElse(null);
        window.initOwner(owner);
        window.setTitle("Changes — " + directory.getFileName());
        var scene = new Scene(repo == null ? notARepository() : build(), 1000, 650);
        scene.getStylesheets().add(ChangesWindow.class.getResource("/app.css").toExternalForm());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ESCAPE), () -> Dialogs.closeLater(window));
        window.setScene(scene);
    }

    public void show() {
        window.show();
        window.toFront();
        if (repo != null) {
            reload();
        }
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
        commit.setOnAction(e -> commitAll());
        discard.setOnAction(e -> discardSelected());
        refresh.setOnAction(e -> reload());
        discard.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        var bar = new HBox(8, message, commit, discard, refresh);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6));

        var root = new BorderPane(split, null, null, bar, null);
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
        background(() -> {
            try {
                repo.commitAll(text);
                Platform.runLater(() -> {
                    message.clear();
                    reload();
                });
            } catch (GitRepo.GitException e) {
                Platform.runLater(() -> Dialogs.error(window, "Cannot commit", e.getMessage()));
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
