package dev.kamil.fabric.git;

import dev.kamil.fabric.Dialogs;
import dev.kamil.fabric.terminal.TerminalTab;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;

/** Branch switcher under a session's branch label: filter, grouped list, checkout and new branch. */
public final class BranchPopup {

    /** One list row: a group header (branch null) or a branch. */
    record Row(String header, GitRepo.Branch branch) {
        boolean isHeader() {
            return header != null;
        }
    }

    private final GitRepo repo;
    private final TerminalTab tab;
    private final Runnable onDone;
    private final Popup popup = new Popup();
    private final TextField filter = new TextField();
    private final ListView<Row> list = new ListView<>();
    private Window owner;
    private List<GitRepo.Branch> branches = List.of();
    private List<String> recent = List.of();

    public BranchPopup(GitRepo repo, TerminalTab tab, Runnable onDone) {
        this.repo = repo;
        this.tab = tab;
        this.onDone = onDone;

        filter.setPromptText("Filter branches");
        filter.getStyleClass().add("branch-filter");
        list.getStyleClass().add("branch-list");
        list.setPrefSize(320, 320);
        list.setCellFactory(v -> new RowCell());
        list.setPlaceholder(new Label("Loading…"));
        VBox.setVgrow(list, Priority.ALWAYS);

        var create = new Button("+ New branch…");
        create.getStyleClass().add("branch-new");
        create.setMaxWidth(Double.MAX_VALUE);
        create.setOnAction(e -> newBranch());

        var root = new VBox(6, filter, list, create, Dialogs.keyHint("↑↓ choose   Enter switch   Esc close"));
        root.getStyleClass().add("branch-popup");
        root.setPadding(new Insets(8));
        root.getStylesheets().add(BranchPopup.class.getResource("/app.css").toExternalForm());
        popup.getContent().add(root);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);

        filter.textProperty().addListener((o, was, now) -> render());
        filter.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.DOWN) {
                list.requestFocus();
                move(1);
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
                activate();
                e.consume();
            }
        });
        list.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.DOWN) {
                move(1);
                e.consume();
            } else if (e.getCode() == KeyCode.UP) {
                move(-1);
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
                activate();
                e.consume();
            }
        });
    }

    /** Shows the popup below {@code anchor} and loads the branches in the background. */
    public void show(Node anchor) {
        if (anchor.getScene() == null || anchor.getScene().getWindow() == null) {
            return;
        }
        owner = anchor.getScene().getWindow();
        var bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        popup.show(owner, bounds.getMinX(), bounds.getMaxY() + 2);
        filter.requestFocus();
        var thread = new Thread(() -> {
            try {
                var all = repo.branches();
                var rec = repo.recentBranches(5);
                Platform.runLater(() -> {
                    branches = all;
                    recent = rec;
                    render();
                });
            } catch (GitRepo.GitException ex) {
                Platform.runLater(() -> list.setPlaceholder(new Label(ex.getMessage())));
            }
        }, "branch-load");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Rows for the popup: Recent (excluding the current branch), Local, Remote; a group with no match has no
     * header. {@code filter} is a case-insensitive substring.
     */
    static List<Row> rows(List<GitRepo.Branch> branches, List<String> recent, String filter) {
        var needle = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
        var recentRows = new ArrayList<Row>();
        for (var name : recent) {
            branches.stream()
                    .filter(b -> !b.remote() && !b.current() && b.name().equals(name))
                    .filter(b -> matches(b, needle))
                    .findFirst()
                    .filter(b -> recentRows.stream().noneMatch(r -> r.branch().name().equals(b.name())))
                    .ifPresent(b -> recentRows.add(new Row(null, b)));
        }
        var result = new ArrayList<Row>();
        addGroup(result, "Recent", recentRows);
        addGroup(result, "Local", branches.stream()
                .filter(b -> !b.remote() && matches(b, needle)).map(b -> new Row(null, b)).toList());
        addGroup(result, "Remote", branches.stream()
                .filter(b -> b.remote() && matches(b, needle)).map(b -> new Row(null, b)).toList());
        return result;
    }

    private static boolean matches(GitRepo.Branch b, String needle) {
        return b.name().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static void addGroup(List<Row> out, String header, List<Row> group) {
        if (!group.isEmpty()) {
            out.add(new Row(header, null));
            out.addAll(group);
        }
    }

    private void render() {
        var rows = rows(branches, recent, filter.getText());
        list.getItems().setAll(rows);
        if (rows.isEmpty() && !branches.isEmpty()) {
            list.setPlaceholder(new Label("No matching branches"));
        } else if (rows.isEmpty()) {
            list.setPlaceholder(new Label("No branches"));
        }
        list.getSelectionModel().clearSelection();
        move(1);
    }

    /** Moves the selection to the next branch row in {@code dir}, skipping headers. */
    private void move(int dir) {
        var items = list.getItems();
        int i = list.getSelectionModel().getSelectedIndex();
        i = i < 0 && dir < 0 ? items.size() : i;
        for (i += dir; i >= 0 && i < items.size(); i += dir) {
            if (!items.get(i).isHeader()) {
                list.getSelectionModel().select(i);
                list.scrollTo(i);
                return;
            }
        }
    }

    private void activate() {
        var row = list.getSelectionModel().getSelectedItem();
        if (row != null && !row.isHeader() && !row.branch().current()) {
            checkout(row.branch());
        }
    }

    private void checkout(GitRepo.Branch branch) {
        var window = owner;
        Platform.runLater(() -> {
            popup.hide();
            BranchCheckout.run(window, repo, branch, tab, onDone);
        });
    }

    private void newBranch() {
        var window = owner;
        popup.hide();
        var dialog = new TextInputDialog();
        dialog.setTitle("FabricManager");
        dialog.setHeaderText("New branch from HEAD");
        dialog.setContentText("Name:");
        Dialogs.style(dialog.getDialogPane());
        dialog.initOwner(window);
        dialog.showAndWait().map(String::strip).filter(n -> !n.isEmpty()).ifPresent(name -> {
            var thread = new Thread(() -> {
                try {
                    repo.createBranch(name, true);
                    Platform.runLater(() -> {
                        onDone.run();
                        tab.focus();
                    });
                } catch (GitRepo.GitException ex) {
                    Platform.runLater(() -> Dialogs.error(window, "Cannot create " + name, ex.getMessage()));
                }
            }, "branch-create");
            thread.setDaemon(true);
            thread.start();
        });
    }

    private final class RowCell extends ListCell<Row> {

        RowCell() {
            setPrefWidth(0);
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !isEmpty()) {
                    activate();
                }
            });
        }

        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            getStyleClass().removeAll("branch-header", "branch-remote", "branch-current");
            if (empty || row == null) {
                setGraphic(null);
                setText(null);
                setMouseTransparent(false);
                return;
            }
            setText(null);
            if (row.isHeader()) {
                var label = new Label(row.header());
                getStyleClass().add("branch-header");
                setMouseTransparent(true);
                setGraphic(label);
                return;
            }
            setMouseTransparent(false);
            var b = row.branch();
            var name = new Label((b.current() ? "✓ " : "") + b.name());
            name.setMinWidth(0);
            var spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            var line = new HBox(6, name, spacer);
            line.setAlignment(Pos.CENTER_LEFT);
            if (b.upstream() != null && !b.upstream().isEmpty()) {
                var up = new Label(b.upstream());
                up.getStyleClass().add("branch-upstream");
                line.getChildren().add(up);
            }
            if (b.current()) {
                getStyleClass().add("branch-current");
            }
            if (b.remote()) {
                getStyleClass().add("branch-remote");
            }
            setGraphic(line);
        }
    }
}
