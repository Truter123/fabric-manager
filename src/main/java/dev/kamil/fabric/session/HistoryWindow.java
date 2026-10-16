package dev.kamil.fabric.session;

import dev.kamil.fabric.Dialogs;
import dev.kamil.fabric.git.BranchCheckout;
import dev.kamil.fabric.terminal.TerminalSettings;
import dev.kamil.fabric.git.BranchTree;
import dev.kamil.fabric.git.ChangesTree;
import dev.kamil.fabric.git.CommitGraph;
import dev.kamil.fabric.git.DiffView;
import dev.kamil.fabric.git.GitRepo;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Non-modal git log window in the style of IntelliJ IDEA: branch tree, commit table with graph and ref labels,
 * changed files tree, commit details and the selected file's diff.
 *
 * <p>Esc closes, Ctrl+F filters the loaded commits, F5 reloads. One window per repository root.
 */
public final class HistoryWindow {

    private static final int PAGE = 200;
    private static final int MAX_DIFF_LINES = 5000;
    private static final double ROW_HEIGHT = 24;
    private static final double LANE_WIDTH = 14;
    private static final int MAX_DRAWN_LANES = 24;
    private static final String ALL_USERS = "All";
    private static final List<String> PALETTE = List.of(
            "#7aa2f7", "#9ece6a", "#e0af68", "#bb9af7", "#7dcfff", "#f7768e", "#ff9e64", "#73daca");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm");
    private static final Map<Path, HistoryWindow> OPEN = new HashMap<>();

    /** How a ref label is drawn. */
    enum RefKind { HEAD, CURRENT, LOCAL, REMOTE, TAG }

    /** How a diff line is coloured. */
    public enum DiffKind { ADD, REMOVE, HUNK, HEADER, CONTEXT }

    /** One ref label on a commit row. */
    record Chip(String text, RefKind kind) {
    }

    private final GitRepo repo;
    private final Consumer<Path> onOpenSession;
    private final Stage window = new Stage();
    private final javafx.collections.ObservableList<GitRepo.Commit> commits = FXCollections.observableArrayList();
    private final FilteredList<GitRepo.Commit> filtered = new FilteredList<>(commits);
    private final TableView<GitRepo.Commit> table = new TableView<>(filtered);
    private final TextField filter = new TextField();
    private final Label branchLabel = new Label("Branch: All");
    private final ComboBox<String> userBox = new ComboBox<>();
    private final TextField branchFilter = new TextField();
    private final TreeView<BranchTree.Node> branchTree = new TreeView<>();
    private final TreeView<ChangesTree.Node> changesTree = new TreeView<>();
    private final Label filesHeader = new Label();
    private final Label subject = new Label();
    private final TextArea body = new TextArea();
    private final Label meta = new Label();
    private final Label inBranches = new Label();
    private final Label diffPath = new Label();
    private final Label diffAdded = new Label();
    private final Label diffRemoved = new Label();
    private final ListView<DiffView.Row> diff = new ListView<>();
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicInteger treeGeneration = new AtomicInteger();
    private final Map<String, CommitGraph.Row> graphRows = new HashMap<>();
    private final Map<String, GitRepo.Branch> branchByName = new HashMap<>();
    /** Folders the user closed; everything else starts open, like IntelliJ's branch tree. */
    private final Set<String> collapsed = new HashSet<>();
    private Set<String> remotes = Set.of();
    private List<GitRepo.Branch> branchList = List.of();
    private List<GitRepo.Worktree> worktreeList = List.of();
    private List<GitRepo.Stash> stashList = List.of();
    private List<String> tagList = List.of();
    private String currentBranch;
    private String selectedRef;
    private CommitGraph graph = new CommitGraph();
    private int maxLanes = 1;
    private boolean filtering;
    private boolean rebuilding;
    private boolean loading;
    private boolean hasMore = true;

    /**
     * Opens the window for {@code repo}, or brings the existing one for the same root to front;
     * {@code onOpenSession} starts a Claude session in a folder (double-click on a worktree).
     */
    public static void open(Window owner, GitRepo repo, String branch, Consumer<Path> onOpenSession) {
        var key = repo.root().toAbsolutePath().normalize();
        var existing = OPEN.get(key);
        if (existing != null) {
            existing.window.show();
            existing.window.setIconified(false);
            existing.window.toFront();
            existing.window.requestFocus();
            return;
        }
        var created = new HistoryWindow(owner, repo, branch, onOpenSession);
        OPEN.put(key, created);
        created.window.setOnHidden(e -> OPEN.remove(key));
        created.window.show();
        created.reload();
    }

    private HistoryWindow(Window owner, GitRepo repo, String branch, Consumer<Path> onOpenSession) {
        this.repo = repo;
        this.onOpenSession = onOpenSession;
        var name = repo.root().getFileName() == null ? repo.root().toString() : repo.root().getFileName().toString();
        window.initOwner(owner);
        window.setTitle("History — " + name + " (" + branch + ")");

        var scene = new Scene(build(), 1400, 850);
        scene.getStylesheets().add(HistoryWindow.class.getResource("/app.css").toExternalForm());
        var accelerators = scene.getAccelerators();
        accelerators.put(new KeyCodeCombination(KeyCode.ESCAPE), () -> Dialogs.closeLater(window));
        accelerators.put(new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN), () -> {
            filter.requestFocus();
            filter.selectAll();
        });
        accelerators.put(new KeyCodeCombination(KeyCode.F5), this::reload);
        window.setScene(scene);
    }

    private BorderPane build() {
        var mono = "-fx-font-family: '" + fontFamily() + "'; -fx-font-size: 12px;";

        // Left: branches.
        branchFilter.setPromptText("Filter branches");
        branchFilter.textProperty().addListener((o, a, text) -> renderBranches());
        branchTree.setShowRoot(false);
        branchTree.setCellFactory(v -> new BranchCell());
        branchTree.getSelectionModel().selectedItemProperty().addListener((o, a, item) -> {
            if (rebuilding || item == null || item.getValue().ref() == null) {
                return;
            }
            var ref = BranchTree.ALL.equals(item.getValue().ref()) ? null : item.getValue().ref();
            if (!Objects.equals(ref, selectedRef)) {
                selectedRef = ref;
                branchLabel.setText("Branch: " + (ref == null ? "All" : ref));
                reloadLog();
            }
        });
        VBox.setVgrow(branchTree, Priority.ALWAYS);
        var left = new VBox(6, branchFilter, branchTree);
        left.setPadding(new Insets(6));

        // Centre: toolbar + table, diff.
        filter.setPromptText("Filter by subject, author or hash (Ctrl+F)");
        filter.textProperty().addListener((o, a, text) -> updatePredicate());
        HBox.setHgrow(filter, Priority.ALWAYS);
        branchLabel.getStyleClass().add("history-meta");
        userBox.getItems().add(ALL_USERS);
        userBox.setValue(ALL_USERS);
        userBox.valueProperty().addListener((o, a, user) -> updatePredicate());
        var refresh = new Button("⟳");
        refresh.setTooltip(new Tooltip("Refresh (F5)"));
        refresh.setOnAction(e -> reload());
        var bar = new HBox(8, filter, branchLabel, new Label("User:"), userBox, refresh);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6));
        buildTable();
        var tablePane = new BorderPane(table, bar, null, null, null);

        diffPath.setStyle("-fx-font-weight: bold;");
        diffAdded.getStyleClass().add("diff-count-add");
        diffRemoved.getStyleClass().add("diff-count-remove");
        var diffBar = new HBox(10, diffPath, diffAdded, diffRemoved);
        diffBar.setAlignment(Pos.CENTER_LEFT);
        diffBar.getStyleClass().add("diff-bar");
        diff.getStyleClass().add("diff-view");
        diff.setStyle(mono);
        diff.setCellFactory(v -> new DiffCell());
        diff.setPlaceholder(new Label("No diff"));
        var diffPane = new BorderPane(diff, diffBar, null, null, null);

        var centre = new SplitPane(tablePane, diffPane);
        centre.setOrientation(Orientation.VERTICAL);
        centre.setDividerPositions(0.6);

        // Right: changed files, commit details.
        filesHeader.getStyleClass().add("history-meta");
        changesTree.setShowRoot(false);
        changesTree.setCellFactory(v -> new ChangeCell());
        changesTree.getSelectionModel().selectedItemProperty().addListener((o, a, item) -> {
            if (item != null && item.getValue().file() != null) {
                showDiff(item.getValue().file());
            }
        });
        VBox.setVgrow(changesTree, Priority.ALWAYS);
        var files = new VBox(4, filesHeader, changesTree);
        files.setPadding(new Insets(6));

        subject.setWrapText(true);
        subject.getStyleClass().add("history-subject");
        body.setEditable(false);
        body.setWrapText(true);
        body.setPrefRowCount(1);
        body.managedProperty().bind(body.visibleProperty());
        body.textProperty().addListener((o, was, text) -> {
            var lines = text == null || text.isBlank() ? 0 : (int) text.strip().lines().count();
            body.setVisible(lines > 0);
            body.setPrefRowCount(Math.min(Math.max(lines, 1), 10));
        });
        meta.setWrapText(true);
        meta.getStyleClass().add("history-meta");
        inBranches.setWrapText(true);
        inBranches.getStyleClass().add("history-meta");
        var details = new VBox(6, subject, body, meta, inBranches);
        details.setFillWidth(true);
        details.setPadding(new Insets(6));
        var right = new SplitPane(files, details);
        right.setOrientation(Orientation.VERTICAL);
        right.setDividerPositions(0.55);

        var split = new SplitPane(left, centre, right);
        split.setDividerPositions(0.16, 0.76);
        SplitPane.setResizableWithParent(left, false);

        var root = new BorderPane(split);
        root.getStyleClass().addAll("root", "history");
        return root;
    }

    private void buildTable() {
        var subjectColumn = new TableColumn<GitRepo.Commit, GitRepo.Commit>("Subject");
        subjectColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        subjectColumn.setCellFactory(c -> new SubjectCell());
        subjectColumn.setSortable(false);
        var author = new TableColumn<GitRepo.Commit, String>("Author");
        author.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().author()));
        var date = new TableColumn<GitRepo.Commit, GitRepo.Commit>("Date");
        date.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        date.setCellFactory(c -> new DateCell());
        var hash = new TableColumn<GitRepo.Commit, String>("Hash");
        hash.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().shortHash()));
        hash.setStyle("-fx-font-family: '" + fontFamily() + "'; -fx-text-fill: -fm-muted;");
        table.getColumns().addAll(List.of(subjectColumn, author, date, hash));
        // Fixed narrow columns; Subject takes the rest (the scroll bar gutter is the 20).
        author.setPrefWidth(150);
        date.setPrefWidth(130);
        hash.setPrefWidth(80);
        subjectColumn.prefWidthProperty().bind(table.widthProperty().subtract(150 + 130 + 80 + 20));
        table.setFixedCellSize(ROW_HEIGHT);
        table.setPlaceholder(new Label("Loading…"));
        table.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(GitRepo.Commit item, boolean empty) {
                super.updateItem(item, empty);
                setStyle(!empty && item != null && isHead(item) ? "-fx-font-weight: bold;" : "");
                if (!empty && getIndex() >= filtered.size() - 20) {
                    loadMore();
                }
            }
        });
        table.getSelectionModel().selectedItemProperty().addListener((o, a, commit) -> showCommit(commit));
    }

    private static String fontFamily() {
        try {
            return new TerminalSettings().getTerminalFont().getFamily();
        } catch (RuntimeException | LinkageError e) {
            return "monospace";
        }
    }

    private void updatePredicate() {
        var needle = filter.getText().strip().toLowerCase(Locale.ROOT);
        var user = userBox.getValue() == null || ALL_USERS.equals(userBox.getValue()) ? null : userBox.getValue();
        filtering = !needle.isEmpty() || user != null;
        filtered.setPredicate(c -> matches(c, needle) && (user == null || c.author().equals(user)));
        table.refresh();
    }

    /** Reloads branches and the log. */
    private void reload() {
        loadBranches();
        reloadLog();
    }

    private void loadBranches() {
        var gen = treeGeneration.incrementAndGet();
        background(() -> {
            try {
                var all = repo.branches();
                var current = repo.currentBranch();
                var trees = optional("worktrees", repo::worktrees);
                var stashes = optional("stashes", repo::stashes);
                var tags = optional("tags", repo::tags);
                Platform.runLater(() -> {
                    if (gen != treeGeneration.get()) {
                        return;
                    }
                    branchList = all;
                    worktreeList = trees;
                    stashList = stashes;
                    tagList = tags;
                    currentBranch = current;
                    branchByName.clear();
                    all.forEach(b -> branchByName.put(b.name(), b));
                    var names = new HashSet<String>();
                    all.stream().filter(GitRepo.Branch::remote)
                            .forEach(b -> names.add(b.name().substring(0, Math.max(0, b.name().indexOf('/')))));
                    remotes = names;
                    renderBranches();
                    table.refresh();
                });
            } catch (GitRepo.GitException e) {
                Platform.runLater(() -> {
                    if (gen == treeGeneration.get()) {
                        var root = new TreeItem<BranchTree.Node>();
                        root.getChildren().add(new TreeItem<>(new BranchTree.Node(String.valueOf(e.getMessage()), null, List.of())));
                        branchTree.setRoot(root);
                    }
                });
            }
        });
    }

    /** Runs {@code load}; a failure is logged and yields an empty list so the branch list still shows. */
    private static <T> List<T> optional(String what, GitCall<T> load) {
        try {
            return load.call();
        } catch (GitRepo.GitException | RuntimeException e) {
            System.err.println("Cannot load " + what + ": " + e.getMessage());
            return List.of();
        }
    }

    @FunctionalInterface
    private interface GitCall<T> {
        List<T> call() throws GitRepo.GitException;
    }

    private void renderBranches() {
        var filterText = branchFilter.getText();
        var root = new TreeItem<BranchTree.Node>();
        for (var node : BranchTree.tree(branchList, currentBranch, filterText, worktreeList, stashList, tagList)) {
            root.getChildren().add(branchItem(node, "", !filterText.isBlank()));
        }
        rebuilding = true;
        try {
            branchTree.setRoot(root);
            var wanted = findBranch(root, selectedRef == null ? BranchTree.ALL : selectedRef);
            if (wanted != null) {
                branchTree.getSelectionModel().select(wanted);
            }
        } finally {
            rebuilding = false;
        }
    }

    private TreeItem<BranchTree.Node> branchItem(BranchTree.Node node, String path, boolean expandAll) {
        var item = new TreeItem<>(node);
        var key = path + "/" + node.label();
        for (var child : node.children()) {
            item.getChildren().add(branchItem(child, key, expandAll));
        }
        item.setExpanded(!node.children().isEmpty() && (expandAll || !collapsed.contains(key)));
        item.expandedProperty().addListener((o, was, open) -> {
            if (open) {
                collapsed.remove(key);
            } else {
                collapsed.add(key);
            }
        });
        return item;
    }

    private static TreeItem<BranchTree.Node> findBranch(TreeItem<BranchTree.Node> item, String ref) {
        if (item.getValue() != null && ref.equals(item.getValue().ref())) {
            return item;
        }
        for (var child : item.getChildren()) {
            var found = findBranch(child, ref);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void selectBranch(String ref) {
        if (branchTree.getRoot() == null) {
            return;
        }
        var item = findBranch(branchTree.getRoot(), ref);
        if (item != null) {
            branchTree.getSelectionModel().select(item);
        }
    }

    private void newBranchFrom(GitRepo.Branch from) {
        var dialog = new TextInputDialog();
        dialog.setTitle("FabricManager");
        dialog.setHeaderText("New branch from " + from.name());
        dialog.setContentText("Name:");
        Dialogs.style(dialog.getDialogPane());
        dialog.initOwner(window);
        dialog.showAndWait().map(String::strip).filter(n -> !n.isEmpty()).ifPresent(name -> background(() -> {
            try {
                repo.createBranch(name, from.name(), false);
                Platform.runLater(this::reload);
            } catch (GitRepo.GitException ex) {
                Platform.runLater(() -> Dialogs.error(window, "Cannot create " + name, ex.getMessage()));
            }
        }));
    }

    /** Drops the commits and loads the first page of the selected ref. */
    private void reloadLog() {
        generation.incrementAndGet();
        commits.clear();
        graphRows.clear();
        graph = new CommitGraph();
        maxLanes = 1;
        loading = false;
        hasMore = true;
        table.setPlaceholder(new Label("Loading…"));
        clearDetails();
        loadMore();
    }

    private void loadMore() {
        if (loading || !hasMore) {
            return;
        }
        loading = true;
        var gen = generation.get();
        var skip = commits.size();
        var ref = selectedRef;
        background(() -> {
            try {
                var page = repo.log(ref, skip, PAGE);
                Platform.runLater(() -> {
                    if (gen != generation.get()) {
                        return;
                    }
                    loading = false;
                    hasMore = page.size() >= PAGE;
                    var first = commits.isEmpty();
                    var rows = graph.append(page);
                    for (var i = 0; i < Math.min(rows.size(), page.size()); i++) {
                        graphRows.put(page.get(i).hash(), rows.get(i));
                        maxLanes = Math.max(maxLanes, rows.get(i).laneCount());
                    }
                    for (var commit : page) {
                        if (!userBox.getItems().contains(commit.author())) {
                            userBox.getItems().add(commit.author());
                        }
                    }
                    commits.addAll(page);
                    if (commits.isEmpty()) {
                        table.setPlaceholder(new Label("No commits"));
                    } else if (first) {
                        table.getSelectionModel().selectFirst();
                    }
                });
            } catch (GitRepo.GitException e) {
                Platform.runLater(() -> {
                    if (gen == generation.get()) {
                        loading = false;
                        hasMore = false;
                        table.setPlaceholder(new Label(String.valueOf(e.getMessage())));
                    }
                });
            }
        });
    }

    private void clearDetails() {
        subject.setText("");
        body.clear();
        meta.setText("");
        inBranches.setText("");
        filesHeader.setText("");
        changesTree.setRoot(null);
        clearDiff();
    }

    private void clearDiff() {
        diff.getItems().clear();
        diff.setPlaceholder(new Label("No diff"));
        diffPath.setText("");
        diffAdded.setText("");
        diffRemoved.setText("");
    }

    private void showCommit(GitRepo.Commit commit) {
        clearDetails();
        if (commit == null) {
            return;
        }
        subject.setText(commit.subject());
        meta.setText(abbreviate(commit.hash()) + " " + commit.author() + " on " + fullDate(commit.date()));
        inBranches.setText("In branches: …");
        background(() -> {
            try {
                var info = repo.info(commit.hash());
                var changes = repo.files(commit.hash());
                Platform.runLater(() -> {
                    if (table.getSelectionModel().getSelectedItem() != commit) {
                        return;
                    }
                    body.setText(info.body());
                    meta.setText(metaLine(commit, info, ZoneId.systemDefault()));
                    showChanges(changes);
                });
            } catch (GitRepo.GitException e) {
                Platform.runLater(() -> {
                    if (table.getSelectionModel().getSelectedItem() == commit) {
                        body.setText(String.valueOf(e.getMessage()));
                    }
                });
            }
        });
        background(() -> {
            String line;
            try {
                line = branchesLine(repo.branchesContaining(commit.hash()));
            } catch (GitRepo.GitException e) {
                line = "";
            }
            var text = line;
            Platform.runLater(() -> {
                if (table.getSelectionModel().getSelectedItem() == commit) {
                    inBranches.setText(text);
                }
            });
        });
    }

    private void showChanges(List<GitRepo.FileChange> changes) {
        var nodes = ChangesTree.build(changes);
        var root = new TreeItem<ChangesTree.Node>();
        nodes.forEach(n -> root.getChildren().add(changeItem(n)));
        changesTree.setRoot(root);
        filesHeader.setText(changes.size() + (changes.size() == 1 ? " file changed" : " files changed"));
        var first = ChangesTree.first(nodes);
        if (first != null) {
            var item = findChange(root, first);
            if (item != null) {
                changesTree.getSelectionModel().select(item);
            }
        }
    }

    private static TreeItem<ChangesTree.Node> changeItem(ChangesTree.Node node) {
        var item = new TreeItem<>(node);
        node.children().forEach(c -> item.getChildren().add(changeItem(c)));
        item.setExpanded(true);
        return item;
    }

    private static TreeItem<ChangesTree.Node> findChange(TreeItem<ChangesTree.Node> item, GitRepo.FileChange file) {
        if (item.getValue() != null && file.equals(item.getValue().file())) {
            return item;
        }
        for (var child : item.getChildren()) {
            var found = findChange(child, file);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void showDiff(GitRepo.FileChange file) {
        clearDiff();
        var commit = table.getSelectionModel().getSelectedItem();
        if (commit == null) {
            return;
        }
        diffPath.setText(file.oldPath() == null ? file.path() : file.oldPath() + " → " + file.path());
        background(() -> {
            DiffView.Parsed parsed;
            try {
                parsed = DiffView.parse(repo.diff(commit.hash(), file), MAX_DIFF_LINES);
            } catch (GitRepo.GitException e) {
                parsed = new DiffView.Parsed(
                        List.of(new DiffView.Row(0, 0, String.valueOf(e.getMessage()), DiffKind.HEADER)), 0, 0, false);
            }
            var result = parsed;
            Platform.runLater(() -> {
                var selected = changesTree.getSelectionModel().getSelectedItem();
                if (table.getSelectionModel().getSelectedItem() == commit
                        && selected != null && file.equals(selected.getValue().file())) {
                    diff.getItems().setAll(result.rows());
                    if (result.binary()) {
                        diff.setPlaceholder(new Label("Binary file"));
                    }
                    diffAdded.setText("+" + result.added());
                    diffRemoved.setText("−" + result.removed());
                }
            });
        });
    }

    private static void background(Runnable task) {
        Thread.ofPlatform().daemon().name("history-git").start(task);
    }

    private static boolean matches(GitRepo.Commit c, String needle) {
        return needle.isEmpty()
                || c.subject().toLowerCase(Locale.ROOT).contains(needle)
                || c.author().toLowerCase(Locale.ROOT).contains(needle)
                || c.hash().toLowerCase(Locale.ROOT).startsWith(needle);
    }

    private static String abbreviate(String hash) {
        return hash.length() > 7 ? hash.substring(0, 7) : hash;
    }

    private static String fullDate(Instant date) {
        return FULL.format(date.atZone(ZoneId.systemDefault()));
    }

    // ---- pure helpers (tested) ----

    /** "Today 19:20", "Yesterday 08:01", else "dd.MM.yy HH:mm". */
    static String formatDate(Instant date, Instant now, ZoneId zone) {
        var local = date.atZone(zone);
        var today = now.atZone(zone).toLocalDate();
        if (local.toLocalDate().equals(today)) {
            return "Today " + TIME.format(local);
        }
        if (local.toLocalDate().equals(today.minusDays(1))) {
            return "Yesterday " + TIME.format(local);
        }
        return SHORT.format(local);
    }

    /** Ref labels of a commit, ordered HEAD, current branch, local, remote, tags. "HEAD -> x" becomes two chips. */
    static List<Chip> chips(List<String> refs, Set<String> remotes) {
        var chips = new ArrayList<Chip>();
        for (var ref : refs) {
            if (ref.startsWith("HEAD -> ")) {
                chips.add(new Chip("HEAD", RefKind.HEAD));
                chips.add(new Chip(ref.substring("HEAD -> ".length()), RefKind.CURRENT));
            } else if (ref.equals("HEAD")) {
                chips.add(new Chip("HEAD", RefKind.HEAD));
            } else if (ref.startsWith("tag:")) {
                chips.add(new Chip(ref, RefKind.TAG));
            } else {
                var slash = ref.indexOf('/');
                var remote = classifyRef(ref) == RefKind.REMOTE || (slash > 0 && remotes.contains(ref.substring(0, slash)));
                chips.add(new Chip(ref, remote ? RefKind.REMOTE : RefKind.LOCAL));
            }
        }
        chips.sort(Comparator.comparing(c -> c.kind().ordinal()));
        return chips;
    }

    /** How many chips are drawn before "+N": two, or three when the list starts with the HEAD chip. */
    static int visibleChips(List<Chip> chips) {
        return !chips.isEmpty() && chips.get(0).kind() == RefKind.HEAD ? 3 : 2;
    }

    /** True for the commit HEAD points at. */
    static boolean isHead(GitRepo.Commit commit) {
        return commit.refs().stream().anyMatch(r -> classifyRef(r) == RefKind.HEAD);
    }

    /** Palette colour for a lane colour index (wraps around). */
    static Color laneColor(int index) {
        return Color.web(PALETTE.get(Math.floorMod(index, PALETTE.size())));
    }

    /** "In 3 branches: a, b, c"; at most 10 names then "and N more". */
    static String branchesLine(List<String> names) {
        if (names.isEmpty()) {
            return "In no branches";
        }
        var shown = String.join(", ", names.subList(0, Math.min(10, names.size())));
        var more = names.size() > 10 ? " and " + (names.size() - 10) + " more" : "";
        return "In " + names.size() + (names.size() == 1 ? " branch: " : " branches: ") + shown + more;
    }

    /** "abc1234 Author email on date", plus "Committed by X on date" only when the committer differs. */
    static String metaLine(GitRepo.Commit commit, GitRepo.CommitInfo info, ZoneId zone) {
        var line = abbreviate(commit.hash()) + " " + commit.author() + " <" + info.authorEmail() + "> on "
                + FULL.format(commit.date().atZone(zone));
        if (!info.committer().equals(commit.author())) {
            line += "\nCommitted by " + info.committer() + " on " + FULL.format(info.commitDate().atZone(zone));
        }
        return line;
    }

    /** "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago", else yyyy-MM-dd. */
    static String relativeDate(Instant date, Instant now, ZoneId zone) {
        var age = Duration.between(date, now);
        if (age.isNegative() || age.toMinutes() < 1) {
            return "just now";
        }
        if (age.toMinutes() < 60) {
            return age.toMinutes() + " min ago";
        }
        if (age.toHours() < 24) {
            return age.toHours() + " h ago";
        }
        var days = ChronoUnit.DAYS.between(date.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate());
        if (days <= 1) {
            return "yesterday";
        }
        if (days < 7) {
            return days + " days ago";
        }
        return DAY.format(LocalDate.ofInstant(date, zone));
    }

    /** Classifies one unified-diff line; {@code inHunk} is true after an "@@" line until the next file header. */
    public static DiffKind classifyDiffLine(String line, boolean inHunk) {
        if (line.startsWith("@@")) {
            return DiffKind.HUNK;
        }
        if (!inHunk) {
            return DiffKind.HEADER;
        }
        if (line.startsWith("diff ")) {
            return DiffKind.HEADER;
        }
        if (line.startsWith("+")) {
            return DiffKind.ADD;
        }
        if (line.startsWith("-")) {
            return DiffKind.REMOVE;
        }
        return DiffKind.CONTEXT;
    }

    /** "HEAD -> x" is HEAD, "tag: x" a tag, "origin/x" (or another remote prefix) remote, anything else local. */
    static RefKind classifyRef(String ref) {
        if (ref.equals("HEAD") || ref.startsWith("HEAD ->")) {
            return RefKind.HEAD;
        }
        if (ref.startsWith("tag:")) {
            return RefKind.TAG;
        }
        if (ref.startsWith("origin/") || ref.startsWith("upstream/") || ref.startsWith("remotes/")) {
            return RefKind.REMOTE;
        }
        return RefKind.LOCAL;
    }

    // ---- cells ----

    private final class SubjectCell extends TableCell<GitRepo.Commit, GitRepo.Commit> {
        SubjectCell() {
            getStyleClass().add("graph-cell");
        }

        @Override
        protected void updateItem(GitRepo.Commit commit, boolean empty) {
            super.updateItem(commit, empty);
            if (empty || commit == null) {
                setGraphic(null);
                return;
            }
            var box = new HBox(6);
            box.setAlignment(Pos.CENTER_LEFT);
            var row = graphRows.get(commit.hash());
            if (row != null) {
                box.getChildren().add(graphCanvas(row));
            }
            var text = new Label(commit.subject());
            text.setMinWidth(0);
            text.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(text, Priority.ALWAYS);
            box.getChildren().add(text);
            var chips = chips(commit.refs(), remotes);
            var visible = visibleChips(chips);
            for (var i = 0; i < Math.min(visible, chips.size()); i++) {
                box.getChildren().add(chipLabel(chips.get(i)));
            }
            if (chips.size() > visible) {
                var rest = chips.subList(visible, chips.size());
                var more = new Label("+" + rest.size());
                more.getStyleClass().addAll("chip", "chip-tag");
                more.setTooltip(new Tooltip(String.join("\n", rest.stream().map(Chip::text).toList())));
                box.getChildren().add(more);
            }
            setGraphic(box);
        }

        private Canvas graphCanvas(CommitGraph.Row row) {
            var width = Math.min(maxLanes, MAX_DRAWN_LANES) * LANE_WIDTH;
            var canvas = new Canvas(width, ROW_HEIGHT);
            var g = canvas.getGraphicsContext2D();
            g.setLineWidth(1.6);
            var middle = ROW_HEIGHT / 2;
            if (!filtering) {
                for (var line : row.lines()) {
                    g.setStroke(laneColor(line.color()));
                    var from = laneX(line.fromLane());
                    var to = laneX(line.toLane());
                    if (line.upper()) {
                        g.strokeLine(from, 0, to, middle);
                    } else {
                        g.strokeLine(from, middle, to, ROW_HEIGHT);
                    }
                }
            }
            g.setFill(laneColor(row.color()));
            g.fillOval(laneX(row.lane()) - 3.5, middle - 3.5, 7, 7);
            return canvas;
        }
    }

    /** Long ref names keep their end, which is the distinctive part: "origin/feature/very-long-name" -> "…/very-long-name". */
    static String shortRef(String ref) {
        var max = 24;
        return ref.length() <= max ? ref : "…" + ref.substring(ref.length() - (max - 1));
    }

    private static double laneX(int lane) {
        return 7 + lane * LANE_WIDTH;
    }

    private static Label chipLabel(Chip chip) {
        var label = new Label(shortRef(chip.text()));
        label.getStyleClass().addAll("chip", "chip-" + chip.kind().name().toLowerCase(Locale.ROOT));
        label.setMinWidth(Region.USE_PREF_SIZE);
        if (!label.getText().equals(chip.text())) {
            label.setTooltip(new Tooltip(chip.text()));
        }
        return label;
    }

    private static final class DateCell extends TableCell<GitRepo.Commit, GitRepo.Commit> {
        @Override
        protected void updateItem(GitRepo.Commit commit, boolean empty) {
            super.updateItem(commit, empty);
            if (empty || commit == null) {
                setText(null);
                setTooltip(null);
                return;
            }
            setText(formatDate(commit.date(), Instant.now(), ZoneId.systemDefault()));
            setTooltip(new Tooltip(commit.date().toString()));
        }
    }

    private final class BranchCell extends TreeCell<BranchTree.Node> {
        @Override
        protected void updateItem(BranchTree.Node node, boolean empty) {
            super.updateItem(node, empty);
            getStyleClass().removeAll("hist-current", "hist-remote");
            if (empty || node == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            setTooltip(null);
            setOnMouseClicked(null);
            var branch = node.ref() == null ? null : branchByName.get(node.ref());
            if (node.worktree() != null) {
                setText(node.label());
                setGraphic(null);
                setContextMenu(null);
                setTooltip(new Tooltip(node.worktree() + "\nDouble-click: open a Claude session here"));
                setOnMouseClicked(e -> {
                    if (e.getClickCount() == 2) {
                        onOpenSession.accept(node.worktree());
                    }
                });
                return;
            }
            if (branch == null || node.ref().startsWith("refs/tags/")) {
                setText(node.label());
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            setText(null);
            var line = new HBox(6, new Label("⎇"), new Label(node.label()));
            if (branch.current()) {
                getStyleClass().add("hist-current");
                var check = new Label("✓");
                check.getStyleClass().add("hist-check");
                line.getChildren().add(check);
            }
            if (branch.remote()) {
                getStyleClass().add("hist-remote");
            }
            setGraphic(line);
            var checkout = new MenuItem("Checkout");
            checkout.setDisable(branch.current());
            checkout.setOnAction(e -> BranchCheckout.run(window, repo, branch, null, HistoryWindow.this::reload));
            var only = new MenuItem("Show only this branch");
            only.setOnAction(e -> selectBranch(branch.name()));
            var create = new MenuItem("New branch from here…");
            create.setOnAction(e -> newBranchFrom(branch));
            setContextMenu(new ContextMenu(checkout, only, create));
        }
    }

    private static final class ChangeCell extends TreeCell<ChangesTree.Node> {
        @Override
        protected void updateItem(ChangesTree.Node node, boolean empty) {
            super.updateItem(node, empty);
            setTooltip(null);
            if (empty || node == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            var file = node.file();
            if (file == null) {
                setText(node.label());
                setGraphic(null);
                return;
            }
            setText(null);
            var status = file.status().toLowerCase(Locale.ROOT);
            var mark = new Label(file.status());
            mark.getStyleClass().addAll("file-status", "status-" + status);
            mark.setMinWidth(14);
            var name = new Label(node.label());
            name.getStyleClass().add("status-" + status);
            if (status.equals("d")) {
                name.getStyleClass().add("file-deleted");
            }
            setGraphic(new HBox(6, mark, name));
            if (file.oldPath() != null) {
                setTooltip(new Tooltip(file.oldPath() + " → " + file.path()));
            }
        }
    }

    private static final class DiffCell extends ListCell<DiffView.Row> {
        private final Label oldNo = gutter();
        private final Label newNo = gutter();
        private final HBox gutters = new HBox(oldNo, newNo);

        private static Label gutter() {
            var label = new Label();
            label.getStyleClass().add("diff-gutter");
            label.setMinWidth(44);
            label.setPrefWidth(44);
            label.setAlignment(Pos.CENTER_RIGHT);
            return label;
        }

        @Override
        protected void updateItem(DiffView.Row row, boolean empty) {
            super.updateItem(row, empty);
            getStyleClass().removeIf(s -> s.startsWith("diff-"));
            if (empty || row == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            oldNo.setText(row.oldNo() == 0 ? "" : String.valueOf(row.oldNo()));
            newNo.setText(row.newNo() == 0 ? "" : String.valueOf(row.newNo()));
            setGraphic(gutters);
            setGraphicTextGap(10);
            setText(row.text());
            getStyleClass().add("diff-" + row.kind().name().toLowerCase(Locale.ROOT));
        }
    }
}
