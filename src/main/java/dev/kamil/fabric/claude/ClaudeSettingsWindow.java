package dev.kamil.fabric.claude;

import dev.kamil.fabric.Dialogs;
import dev.kamil.fabric.session.Account;
import dev.kamil.fabric.terminal.TerminalSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Non-modal window to browse and edit the Claude Code configuration files of a project: a tree of scope, kind and
 * file on the left, a text editor on the right.
 *
 * <p>Ctrl+S saves, F5 reloads, Ctrl+F filters, Esc closes. One window per project directory.
 */
public final class ClaudeSettingsWindow {

    private static final Map<Path, ClaudeSettingsWindow> OPEN = new HashMap<>();

    /** A tree row: a scope or kind group (item is null) or a file. */
    record Node(String label, String description, ClaudeConfig.Item item, List<Node> children, String shadowed) {
        Node(String label, String description, ClaudeConfig.Item item, List<Node> children) {
            this(label, description, item, children, "");
        }

        boolean isFile() {
            return item != null;
        }
    }

    private final Path project;
    private final Account account;
    private final Path home = Path.of(System.getProperty("user.home"));
    private final Stage window = new Stage();
    private final String baseTitle;
    private final TextField filter = new TextField();
    private final TreeView<Node> tree = new TreeView<>();
    private final TextArea editor = new TextArea();
    private final Label hint = new Label();
    private final Label status = new Label();
    private final Label dirtyLabel = new Label();
    private final Button save = new Button("Save");
    /** Bumped by each file load (tree click); a stale read result is dropped. */
    private final AtomicInteger loadGeneration = new AtomicInteger();
    /** Bumped by each rescan (F5); separate from loads so a click does not cancel a pending rescan. */
    private final AtomicInteger scanGeneration = new AtomicInteger();
    private Map<ClaudeConfig.Scope, Map<ClaudeConfig.Kind, List<ClaudeConfig.Item>>> scan = Map.of();
    private ClaudeConfig.Item current;
    private String loadedText = "";
    private FileTime loadedMtime;
    private boolean rebuilding;
    private boolean settingText;

    /** Opens the window for {@code project}, or brings the existing one to front. */
    public static void open(Window owner, Path project, Account account) {
        var key = project.toAbsolutePath().normalize();
        var existing = OPEN.get(key);
        if (existing != null) {
            existing.window.show();
            existing.window.setIconified(false);
            existing.window.toFront();
            existing.window.requestFocus();
            return;
        }
        var created = new ClaudeSettingsWindow(owner, key, account);
        OPEN.put(key, created);
        created.window.setOnHidden(e -> OPEN.remove(key));
        created.window.show();
        created.reload();
    }

    private ClaudeSettingsWindow(Window owner, Path project, Account account) {
        this.project = project;
        this.account = account;
        var name = project.getFileName() == null ? project.toString() : project.getFileName().toString();
        baseTitle = "Claude settings — " + name + (account.isPersonal() ? "" : " (" + account.name() + ")");
        window.initOwner(owner);
        window.setTitle(baseTitle);

        var scene = new Scene(build(), 1200, 800);
        scene.getStylesheets().add(ClaudeSettingsWindow.class.getResource("/app.css").toExternalForm());
        var accelerators = scene.getAccelerators();
        accelerators.put(new KeyCodeCombination(KeyCode.ESCAPE), this::close);
        accelerators.put(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN), this::save);
        accelerators.put(new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN), () -> {
            filter.requestFocus();
            filter.selectAll();
        });
        accelerators.put(new KeyCodeCombination(KeyCode.F5), this::reload);
        accelerators.put(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN), () -> createNew(null));
        window.setOnCloseRequest(e -> {
            if (!discardOk()) {
                e.consume();
            }
        });
        window.setScene(scene);
    }

    private BorderPane build() {
        filter.setPromptText("Filter files (Ctrl+F)");
        filter.textProperty().addListener((o, a, text) -> render(selectedPath()));
        tree.setShowRoot(false);
        tree.setCellFactory(v -> new NodeCell());
        tree.getSelectionModel().selectedItemProperty().addListener((o, was, item) -> {
            if (rebuilding || item == null || !item.getValue().isFile()) {
                return;
            }
            var next = item.getValue().item();
            if (current != null && next.path().equals(current.path())) {
                return;
            }
            if (!discardOk()) {
                rebuilding = true;
                select(current == null ? null : current.path());
                rebuilding = false;
                return;
            }
            load(next);
        });
        VBox.setVgrow(tree, Priority.ALWAYS);
        var newButton = new MenuButton("New…");
        newButton.setTooltip(new Tooltip("New skill, agent, command or rule (Ctrl+N)"));
        for (var kind : ClaudeTemplates.NewKind.values()) {
            var menuItem = new MenuItem(ClaudeTemplates.label(kind));
            menuItem.setOnAction(e -> createNew(kind));
            newButton.getItems().add(menuItem);
        }
        HBox.setHgrow(filter, Priority.ALWAYS);
        var filterRow = new HBox(6, filter, newButton);
        var left = new VBox(6, filterRow, tree);
        left.setPadding(new Insets(6));

        editor.setEditable(false);
        editor.setStyle("-fx-font-family: '" + fontFamily() + "'; -fx-font-size: 13px;");
        editor.textProperty().addListener((o, a, text) -> {
            if (!settingText) {
                updateDirty();
            }
        });
        hint.getStyleClass().add("history-meta");
        save.setDisable(true);
        save.setTooltip(new Tooltip("Save (Ctrl+S)"));
        save.setOnAction(e -> save());
        var refresh = new Button("⟳");
        refresh.setTooltip(new Tooltip("Reload (F5)"));
        refresh.setOnAction(e -> reload());
        HBox.setHgrow(hint, Priority.ALWAYS);
        hint.setMaxWidth(Double.MAX_VALUE);
        var bar = new HBox(8, hint, save, refresh);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(6));
        VBox.setVgrow(editor, Priority.ALWAYS);
        var right = new BorderPane(editor, bar, null, null, null);

        var split = new SplitPane(left, right);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.3);

        status.getStyleClass().add("history-meta");
        dirtyLabel.getStyleClass().add("history-meta");
        var statusBar = new HBox(12, status, dirtyLabel);
        statusBar.setPadding(new Insets(4, 8, 4, 8));
        var rootPane = new BorderPane(split, null, null, statusBar, null);
        rootPane.getStyleClass().addAll("history", "claude-settings");
        return rootPane;
    }

    // ---- pure parts ----

    /** Scope, kind and file rows for the scan, keeping files that match {@code filter}; empty groups are dropped. */
    static List<Node> buildTree(Map<ClaudeConfig.Scope, Map<ClaudeConfig.Kind, List<ClaudeConfig.Item>>> scan,
            String filter) {
        var scopes = new ArrayList<Node>();
        var shadows = ClaudeConfig.shadowing(scan);
        for (var scope : ClaudeConfig.Scope.values()) {
            var kinds = scan.get(scope);
            if (kinds == null) {
                continue;
            }
            var kindNodes = new ArrayList<Node>();
            for (var kind : ClaudeConfig.Kind.values()) {
                var files = new ArrayList<Node>();
                for (var item : kinds.getOrDefault(kind, List.of())) {
                    if (matches(item, filter)) {
                        files.add(new Node(item.name(), item.description(), item, List.of(),
                                shadows.getOrDefault(item.path(), "")));
                    }
                }
                if (!files.isEmpty()) {
                    kindNodes.add(new Node(kindLabel(kind), "", null, files));
                }
            }
            if (!kindNodes.isEmpty()) {
                scopes.add(new Node(scopeLabel(scope), "", null, kindNodes));
            }
        }
        return scopes;
    }

    /** True when the filter is blank or occurs (ignoring case) in the item's name or description. */
    static boolean matches(ClaudeConfig.Item item, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        var needle = filter.strip().toLowerCase(Locale.ROOT);
        return item.name().toLowerCase(Locale.ROOT).contains(needle)
                || item.description().toLowerCase(Locale.ROOT).contains(needle);
    }

    /** True when the editor text differs from what was loaded. */
    static boolean isDirty(String loaded, String text) {
        return !(loaded == null ? "" : loaded).equals(text == null ? "" : text);
    }

    /** Why an item cannot be edited, or null when it can. */
    static String readOnlyReason(ClaudeConfig.Item item) {
        if (!item.readOnly()) {
            return null;
        }
        if (item.path().getFileName().toString().equals(".claude.json")) {
            return "Claude's own state — use `claude mcp`";
        }
        return "managed by your admin";
    }

    static String scopeLabel(ClaudeConfig.Scope scope) {
        return switch (scope) {
            case USER -> "User (~/.claude)";
            case PROJECT -> "Project";
            case LOCAL -> "Local";
            case MANAGED -> "Managed";
        };
    }

    static String kindLabel(ClaudeConfig.Kind kind) {
        return switch (kind) {
            case SETTINGS -> "Settings";
            case MEMORY -> "Memory";
            case SKILLS -> "Skills";
            case AGENTS -> "Agents";
            case COMMANDS -> "Commands";
            case OUTPUT_STYLES -> "Output styles";
            case MCP -> "MCP";
            case KEYBINDINGS -> "Keybindings";
        };
    }

    // ---- behaviour ----

    private boolean dirty() {
        return current != null && readOnlyReason(current) == null && isDirty(loadedText, editor.getText());
    }

    private boolean discardOk() {
        return !dirty() || Dialogs.confirm(window, "Discard changes", current.name() + " has unsaved changes.", "Discard");
    }

    private void close() {
        if (discardOk()) {
            window.close();
        }
    }

    private void updateDirty() {
        var d = dirty();
        dirtyLabel.setText(d ? "● modified" : "");
        window.setTitle(d ? "● " + baseTitle : baseTitle);
    }

    private Path selectedPath() {
        return current == null ? null : current.path();
    }

    /** Rescans in the background; keeps the selection, and reloads the text of an unmodified file. */
    private void reload() {
        var gen = scanGeneration.incrementAndGet();
        Thread.ofPlatform().daemon().name("claude-settings-scan").start(() -> {
            var result = ClaudeConfig.scan(project, home, account, ClaudeConfig.MANAGED_ROOT);
            Platform.runLater(() -> {
                if (gen != scanGeneration.get()) {
                    return;
                }
                scan = result;
                var keep = selectedPath();
                render(keep);
                if (keep != null && !dirty()) {
                    scan.values().stream().flatMap(m -> m.values().stream()).flatMap(List::stream)
                            .filter(i -> i.path().equals(keep)).findFirst().ifPresentOrElse(this::load, this::clear);
                }
            });
        });
    }

    private void render(Path keep) {
        rebuilding = true;
        var root = new TreeItem<Node>(new Node("", "", null, List.of()));
        for (var scope : buildTree(scan, filter.getText())) {
            root.getChildren().add(toTreeItem(scope));
        }
        root.setExpanded(true);
        tree.setRoot(root);
        select(keep);
        rebuilding = false;
    }

    private static TreeItem<Node> toTreeItem(Node node) {
        var item = new TreeItem<>(node);
        node.children().forEach(c -> item.getChildren().add(toTreeItem(c)));
        item.setExpanded(true);
        return item;
    }

    private void select(Path path) {
        if (path == null || tree.getRoot() == null) {
            tree.getSelectionModel().clearSelection();
            return;
        }
        var found = find(tree.getRoot(), path);
        if (found == null) {
            tree.getSelectionModel().clearSelection();
        } else {
            tree.getSelectionModel().select(found);
        }
    }

    private static TreeItem<Node> find(TreeItem<Node> item, Path path) {
        if (item.getValue().isFile() && item.getValue().item().path().equals(path)) {
            return item;
        }
        for (var child : item.getChildren()) {
            var found = find(child, path);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void clear() {
        current = null;
        settingText = true;
        editor.clear();
        settingText = false;
        editor.setEditable(false);
        save.setDisable(true);
        hint.setText("");
        status.setText("");
        loadedText = "";
        updateDirty();
    }

    private void load(ClaudeConfig.Item item) {
        var gen = loadGeneration.incrementAndGet();
        current = item;
        status.setText(item.path().toString());
        var reason = readOnlyReason(item);
        hint.setText(reason == null ? "" : "Read-only: " + reason);
        Thread.ofPlatform().daemon().name("claude-settings-read").start(() -> {
            try {
                var text = Files.readString(item.path(), StandardCharsets.UTF_8);
                var mtime = Files.getLastModifiedTime(item.path());
                Platform.runLater(() -> {
                    if (gen != loadGeneration.get() || current != item) {
                        return;
                    }
                    loadedText = text;
                    loadedMtime = mtime;
                    settingText = true;
                    editor.setText(text);
                    settingText = false;
                    editor.positionCaret(0);
                    editor.setEditable(reason == null);
                    save.setDisable(reason != null);
                    updateDirty();
                });
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> {
                    if (gen == loadGeneration.get()) {
                        Dialogs.error(window, "Cannot read file", item.path() + ": " + e.getMessage());
                    }
                });
            }
        });
    }

    private void save() {
        var item = current;
        if (item == null || readOnlyReason(item) != null) {
            return;
        }
        var text = editor.getText();
        var problems = ClaudeValidation.problems(item, kindOf(item), text);
        if (!problems.isEmpty()) {
            Dialogs.error(window, "Not saved", String.join("\n", problems));
            return;
        }
        var expected = loadedMtime;
        save.setDisable(true);
        Thread.ofPlatform().daemon().name("claude-settings-check").start(() -> {
            try {
                if (expected != null && !Files.getLastModifiedTime(item.path()).equals(expected)) {
                    Platform.runLater(() -> {
                        if (Dialogs.confirm(window, "File changed on disk",
                                item.path() + " was modified since it was loaded.", "Overwrite")) {
                            write(item, text);
                        } else {
                            save.setDisable(false);
                        }
                    });
                    return;
                }
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> {
                    save.setDisable(false);
                    Dialogs.error(window, "Cannot save", item.path() + ": " + e.getMessage());
                });
                return;
            }
            Platform.runLater(() -> write(item, text));
        });
    }

    /** Writes the file on a background thread; the save button stays disabled until it is done. */
    private void write(ClaudeConfig.Item item, String text) {
        save.setDisable(true);
        Thread.ofPlatform().daemon().name("claude-settings-write").start(() -> {
            try {
                Files.writeString(item.path(), text, StandardCharsets.UTF_8);
                var mtime = Files.getLastModifiedTime(item.path());
                var result = ClaudeConfig.scan(project, home, account, ClaudeConfig.MANAGED_ROOT);
                Platform.runLater(() -> {
                    save.setDisable(false);
                    if (current == item) {
                        loadedText = text;
                        loadedMtime = mtime;
                        updateDirty();
                    }
                    scan = result;
                    render(selectedPath());
                });
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> {
                    save.setDisable(false);
                    Dialogs.error(window, "Cannot save", item.path() + ": " + e.getMessage());
                });
            }
        });
    }

    /** Asks for scope and name, writes the starter file, rescans, selects it and focuses the editor. */
    private void createNew(ClaudeTemplates.NewKind preset) {
        if (!discardOk()) {
            return;
        }
        var kindBox = new ComboBox<ClaudeTemplates.NewKind>();
        kindBox.getItems().addAll(ClaudeTemplates.NewKind.values());
        kindBox.setValue(preset == null ? ClaudeTemplates.NewKind.SKILL : preset);
        kindBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(ClaudeTemplates.NewKind k) {
                return k == null ? "" : ClaudeTemplates.label(k);
            }

            @Override
            public ClaudeTemplates.NewKind fromString(String s) {
                return null;
            }
        });
        var scopeBox = new ComboBox<String>();
        scopeBox.getItems().addAll("User (~/.claude)", "Project (.claude)");
        var projectIsHome = project.equals(home.toAbsolutePath().normalize());
        scopeBox.setValue(projectIsHome ? scopeBox.getItems().get(0) : scopeBox.getItems().get(1));
        var nameField = new TextField();
        nameField.setPromptText("lowercase-letters-digits-hyphens");
        var grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.addRow(0, new Label("Type"), kindBox);
        grid.addRow(1, new Label("Scope"), scopeBox);
        grid.addRow(2, new Label("Name"), nameField);
        var create = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        var dialog = new Dialog<ButtonType>();
        dialog.setTitle("FabricManager");
        dialog.setHeaderText("New Claude item");
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(create, ButtonType.CANCEL);
        Dialogs.style(dialog.getDialogPane());
        dialog.initOwner(window);
        var createButton = dialog.getDialogPane().lookupButton(create);
        createButton.setDisable(true);
        nameField.textProperty().addListener((o, a, text) -> createButton.setDisable(!ClaudeTemplates.validName(text)));
        nameField.setOnAction(e -> {
            if (!createButton.isDisabled()) {
                ((Button) createButton).fire();
            }
        });
        Platform.runLater(nameField::requestFocus);
        if (dialog.showAndWait().filter(create::equals).isEmpty()) {
            return;
        }
        var kind = kindBox.getValue();
        var name = nameField.getText();
        var base = scopeBox.getValue().startsWith("User") ? account.configDir() : project.resolve(".claude");
        var file = ClaudeTemplates.target(base, kind, name);
        var text = ClaudeTemplates.template(kind, name);
        Thread.ofPlatform().daemon().name("claude-settings-create").start(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                var result = ClaudeConfig.scan(project, home, account, ClaudeConfig.MANAGED_ROOT);
                Platform.runLater(() -> {
                    loadGeneration.incrementAndGet();
                    scanGeneration.incrementAndGet();
                    scan = result;
                    filter.clear();
                    current = null;
                    render(file);
                    scan.values().stream().flatMap(m -> m.values().stream()).flatMap(List::stream)
                            .filter(i -> i.path().equals(file)).findFirst().ifPresent(this::load);
                    editor.requestFocus();
                });
            } catch (FileAlreadyExistsException e) {
                Platform.runLater(() -> Dialogs.error(window, "Already exists", file.toString()));
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> Dialogs.error(window, "Cannot create", file + ": " + e.getMessage()));
            }
        });
    }

    private ClaudeConfig.Kind kindOf(ClaudeConfig.Item item) {
        for (var kinds : scan.values()) {
            for (var entry : kinds.entrySet()) {
                if (entry.getValue().stream().anyMatch(i -> i.path().equals(item.path()))) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private static String fontFamily() {
        try {
            return new TerminalSettings().getTerminalFont().getFamily();
        } catch (RuntimeException | LinkageError e) {
            return "monospace";
        }
    }

    /** Row: name, dimmed description, and a lock for read-only files. */
    private static final class NodeCell extends TreeCell<Node> {
        @Override
        protected void updateItem(Node node, boolean empty) {
            super.updateItem(node, empty);
            if (empty || node == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            setText(null);
            var name = new Label((node.isFile() && node.item().readOnly() ? "🔒 " : "") + node.label());
            var row = new HBox(8, name);
            if (node.isFile() && !node.shadowed().isEmpty()) {
                var shadowed = new Label("— " + node.shadowed());
                shadowed.getStyleClass().add("shadowed");
                row.getChildren().add(shadowed);
            }
            if (node.isFile() && !node.description().isEmpty()) {
                var description = new Label(node.description());
                description.getStyleClass().add("history-meta");
                row.getChildren().add(description);
            }
            setGraphic(row);
        }
    }
}
