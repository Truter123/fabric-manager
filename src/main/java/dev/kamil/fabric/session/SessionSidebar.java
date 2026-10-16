package dev.kamil.fabric.session;

import dev.kamil.fabric.Dialogs;
import dev.kamil.fabric.git.BranchPopup;
import dev.kamil.fabric.git.GitRepo;
import dev.kamil.fabric.settings.AppSettings;
import dev.kamil.fabric.terminal.TerminalTab;
import javafx.application.Platform;
import java.nio.file.Path;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.IdentityHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Left panel: pinned sessions, then the sessions grouped by project, each with a status dot; click selects the tab. */
public final class SessionSidebar extends BorderPane {

    private static final Color GREEN = Color.web("#8fbf7f");
    private static final Color GREY = Color.web("#5c5653");
    private static final Color RED = Color.web("#d96b75");
    private static final Color YELLOW = Color.web("#d9b36c");
    private static final Color BLUE = Color.web("#7fa6d6");
    private static final Color ACCENT = Color.web("#ff7a3d");
    private static final Path HOME = Path.of(System.getProperty("user.home"));
    private static final String FOLDER = "M1 3.5A1.5 1.5 0 0 1 2.5 2h3.2l1.5 1.7h6.3A1.5 1.5 0 0 1 15 5.2v7.3a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 1 12.5z";

    private final TabPane tabs;
    private final VBox content = new VBox(2);
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "session-sampler");
        thread.setDaemon(true);
        return thread;
    });
    /** Tabs as of the last change on the FX thread; the sampler reads this, never the TabPane. */
    private final List<TerminalTab> sampled = new CopyOnWriteArrayList<>();
    /** Projects the user folded; kept in memory only. */
    private final Set<Path> collapsed = new HashSet<>();
    /** Context usage text per session id, set from the hook events. */
    private final Map<String, String> contexts = new java.util.concurrent.ConcurrentHashMap<>();
    private final VBox footer = new VBox();
    /** True while the window is showing and not minimised; written on the FX thread, read by the sampler. */
    private volatile boolean windowActive = true;
    /** Tabs whose sampling failure was already logged. */
    private final Set<TerminalTab> failed = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private Node usageView;
    private Node actionsGrid;
    private Map<TerminalTab, Sample> lastSample = Map.of();
    /** The row menu on screen; rebuilds wait until it closes, since replacing its anchor row hides it. */
    private ContextMenu openMenu;
    private Consumer<TerminalTab> onHistory;
    private BiConsumer<TerminalTab, TerminalTab> onFuse;
    private Consumer<TerminalTab> onDuplicate;
    private Consumer<Path> onNewInProject;
    private BiConsumer<TerminalTab, Boolean> onPin;

    /** What a row shows that comes from the outside world (process table, git). */
    record Sample(TerminalTab.Status status, String branch) {
    }

    /** True when the new snapshot differs from the previous one, i.e. the list needs a refresh. */
    static <K> boolean changed(Map<K, Sample> previous, Map<K, Sample> next) {
        return !Objects.equals(previous, next);
    }

    public SessionSidebar(TabPane tabs) {
        this.tabs = tabs;
        getStyleClass().add("sidebar");
        setPrefWidth(230);
        setMinWidth(230);
        setMaxWidth(230);

        var title = new Label("FabricManager");
        title.getStyleClass().add("sidebar-title");
        var header = new HBox(6, title);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12, 12, 6, 14));
        header.getStyleClass().add("sidebar-header");

        content.getStyleClass().add("sidebar-list");
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        tabs.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> rebuild());

        setTop(header);
        setCenter(scroll);

        sampled.addAll(snapshot(tabs));
        rebuildNow();
        tabs.getTabs().addListener((javafx.collections.ListChangeListener<javafx.scene.control.Tab>) c -> {
            sampled.clear();
            sampled.addAll(snapshot(tabs));
            rebuild();
            requestSample();
        });
        sceneProperty().addListener((o, was, scene) -> {
            if (scene != null) {
                watchWindow(scene.getWindow());
                scene.windowProperty().addListener((o2, w, window) -> watchWindow(window));
            }
        });
        if (getScene() != null) {
            watchWindow(getScene().getWindow());
            getScene().windowProperty().addListener((o2, w, window) -> watchWindow(window));
        }
        sampler.scheduleWithFixedDelay(this::sample, 0, 2, TimeUnit.SECONDS);
    }

    /** FX thread: keeps {@link #windowActive} in step with the window being shown and not minimised. */
    private void watchWindow(javafx.stage.Window window) {
        if (window == null) {
            windowActive = false;
            return;
        }
        Runnable update = () -> {
            var active = window.isShowing() && !(window instanceof javafx.stage.Stage stage && stage.isIconified());
            var wasActive = windowActive;
            windowActive = active;
            if (active && !wasActive) {
                requestSample();
            }
        };
        window.showingProperty().addListener((o, was, now) -> update.run());
        if (window instanceof javafx.stage.Stage stage) {
            stage.iconifiedProperty().addListener((o, was, now) -> update.run());
        }
        update.run();
    }

    private static List<TerminalTab> snapshot(TabPane tabs) {
        return tabs.getTabs().stream().filter(TerminalTab.class::isInstance).map(TerminalTab.class::cast).toList();
    }

    /** Re-reads status and branch soon, off the FX thread. */
    void requestSample() {
        try {
            sampler.execute(this::sample);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // stopped
        }
    }

    /** Sampler thread: refreshes the cached values and the list only when something changed. */
    private void sample() {
        if (!windowActive) {
            return;
        }
        try {
            var next = new IdentityHashMap<TerminalTab, Sample>();
            for (var tab : sampled) {
                try {
                    tab.sample();
                } catch (RuntimeException e) {
                    if (failed.add(tab)) {
                        System.err.println("session sample failed for " + tab.name() + ": " + e);
                    }
                }
                next.put(tab, new Sample(tab.cachedStatus(), tab.cachedBranch()));
            }
            if (changed(lastSample, next)) {
                lastSample = next;
                Platform.runLater(this::rebuild);
            }
        } catch (RuntimeException e) {
            // best effort: try again at the next tick
        }
    }

    /** Redraws the rows from the cached values (call on the FX thread). */
    public void refresh() {
        rebuild();
    }

    /** Called with the tab when "History" is picked in a session's context menu. */
    /** Called with (source, target) when the user fuses a Claude tab into another live Claude tab. */
    public void setOnFuse(BiConsumer<TerminalTab, TerminalTab> onFuse) {
        this.onFuse = onFuse;
    }

    public void setOnHistory(Consumer<TerminalTab> onHistory) {
        this.onHistory = onHistory;
    }

    /** Called with the tab when "Duplicate tab" is picked in a session's context menu. */
    public void setOnDuplicate(Consumer<TerminalTab> onDuplicate) {
        this.onDuplicate = onDuplicate;
    }

    /** Called with the project folder when the "+" of a project group is clicked. */
    public void setOnNewInProject(Consumer<Path> onNewInProject) {
        this.onNewInProject = onNewInProject;
    }

    /** Called with the tab and the new pinned state when "Pin"/"Unpin" is picked. */
    public void setOnPin(BiConsumer<TerminalTab, Boolean> onPin) {
        this.onPin = onPin;
    }

    private void setPinned(TerminalTab tab, boolean pinned) {
        tab.setPinned(pinned);
        if (onPin != null) {
            onPin.accept(tab, pinned);
        }
        rebuild();
    }

    private void openBranches(TerminalTab tab, Node anchor) {
        GitRepo.of(tab.directory()).ifPresent(repo -> new BranchPopup(repo, tab, () -> {
            rebuild();
            requestSample();
        }).show(anchor));
    }

    /** Asks for a new session name; an empty name restores the default. */
    public void rename(TerminalTab tab) {
        var dialog = new TextInputDialog(tab.name());
        dialog.setTitle("FabricManager");
        dialog.setHeaderText("Rename session (empty = default name)");
        dialog.setGraphic(null);
        Dialogs.style(dialog.getDialogPane());
        dialog.initOwner(getScene().getWindow());
        dialog.showAndWait().ifPresent(name -> {
            tab.rename(name);
            rebuild();
        });
    }

    public void stop() {
        sampler.shutdownNow();
    }

    /** Where an action sits in the sidebar bottom, in display order. */
    public enum Group {
        PRIMARY, GRID, FOOTER
    }

    /** One command shown as a sidebar button; the shortcut goes in its tooltip. */
    public record Action(Group group, String icon, String label, String keys, Runnable run) {
    }

    /** Actions by group in {@link Group} order, keeping the given order inside a group; empty groups are omitted. */
    static Map<Group, List<Action>> byGroup(List<Action> actions) {
        var grouped = new java.util.EnumMap<Group, List<Action>>(Group.class);
        for (var action : actions) {
            grouped.computeIfAbsent(action.group(), g -> new java.util.ArrayList<>()).add(action);
        }
        return grouped;
    }

    /** The collapsed state after the toggle is clicked. */
    static boolean toggled(boolean collapsed) {
        return !collapsed;
    }

    private static final String COLLAPSED_KEY = "sidebar.actionsCollapsed";

    /** Text of the collapse toggle: a right chevron when the grid is hidden, a down one when shown. */
    static String toggleText(boolean collapsed) {
        return collapsed ? "\u25B8 More" : "\u25BE Less";
    }

    /** Replaces the bottom actions: primary buttons, a two-column grid of the rest, a footer row with a collapse toggle. */
    public void setActions(List<Action> actions) {
        var box = new VBox(10);
        box.getStyleClass().add("sidebar-actions");
        box.setPadding(new Insets(10));
        var grouped = byGroup(actions);
        var primary = grouped.getOrDefault(Group.PRIMARY, List.of());
        if (!primary.isEmpty()) {
            var row = new HBox(8);
            for (int i = 0; i < primary.size(); i++) {
                var button = actionButton(primary.get(i), false);
                button.getStyleClass().add("action-primary");
                if (i == 0) {
                    button.getStyleClass().add("action-accent");
                }
                button.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(button, Priority.ALWAYS);
                row.getChildren().add(button);
            }
            box.getChildren().add(row);
        }
        var items = grouped.getOrDefault(Group.GRID, List.of());
        var grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        var column = new javafx.scene.layout.ColumnConstraints();
        column.setPercentWidth(50);
        grid.getColumnConstraints().addAll(column, column);
        for (int i = 0; i < items.size(); i++) {
            var button = actionButton(items.get(i), true);
            button.setMaxWidth(Double.MAX_VALUE);
            button.setAlignment(Pos.CENTER_LEFT);
            GridPane.setHgrow(button, Priority.ALWAYS);
            grid.add(button, i % 2, i / 2);
        }
        var collapsed = AppSettings.shared().getBoolean(COLLAPSED_KEY, false);
        grid.setVisible(!collapsed);
        grid.setManaged(!collapsed);
        if (!items.isEmpty()) {
            box.getChildren().add(grid);
        }
        var footer = grouped.getOrDefault(Group.FOOTER, List.of());
        var row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        for (var action : footer) {
            row.getChildren().add(actionButton(action, true));
        }
        if (!items.isEmpty()) {
            var spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            var toggle = new Button(toggleText(collapsed));
            toggle.getStyleClass().add("action-flat");
            toggle.setTooltip(new Tooltip("Show or hide the buttons"));
            toggle.setOnAction(e -> {
                var nowCollapsed = toggled(!grid.isVisible());
                grid.setVisible(!nowCollapsed);
                grid.setManaged(!nowCollapsed);
                toggle.setText(toggleText(nowCollapsed));
                Dialogs.logFailure(() -> AppSettings.shared().setBoolean(COLLAPSED_KEY, nowCollapsed));
            });
            row.getChildren().addAll(spacer, toggle);
        }
        box.getChildren().add(row);
        actionsGrid = box;
        layoutFooter();
    }

    /** A button for one action; {@code compact} adds the icon before the label in a flat style. */
    private static Button actionButton(Action action, boolean compact) {
        var button = new Button(compact ? action.icon() + "  " + action.label() : action.label());
        button.setTooltip(new Tooltip(action.label() + "  " + action.keys()));
        button.setOnAction(e -> action.run().run());
        if (compact) {
            button.getStyleClass().add("action-flat");
        }
        return button;
    }

    private static final String USAGE_COLLAPSED_KEY = "sidebar.usageCollapsed";

    /** Header line of the usage panel: chevron, "Usage", and the summary when collapsed. */
    static String usageHeader(boolean collapsed, String summary) {
        var head = (collapsed ? "\u25B8" : "\u25BE") + " Usage";
        return collapsed && summary != null && !summary.isBlank() ? head + "  " + summary : head;
    }

    private final javafx.beans.property.SimpleBooleanProperty usageExpanded =
            new javafx.beans.property.SimpleBooleanProperty(false);

    /** True while the usage panel is open; the app polls the quota only then. */
    public javafx.beans.property.ReadOnlyBooleanProperty usageExpandedProperty() {
        return usageExpanded;
    }

    /** Puts the usage panel above the button grid, folded behind a one-line header showing {@code summary}. */
    public void setUsageView(Node view, javafx.beans.value.ObservableValue<String> summary) {
        var collapsed = new boolean[] {AppSettings.shared().getBoolean(USAGE_COLLAPSED_KEY, true)};
        var header = new Button();
        header.getStyleClass().add("usage-header");
        header.setMaxWidth(Double.MAX_VALUE);
        header.setAlignment(Pos.CENTER_LEFT);
        view.setVisible(!collapsed[0]);
        usageExpanded.set(!collapsed[0]);
        view.managedProperty().bind(view.visibleProperty());
        Runnable refreshHeader = () -> header.setText(usageHeader(collapsed[0], summary.getValue()));
        summary.addListener((o, was, now) -> refreshHeader.run());
        refreshHeader.run();
        header.setOnAction(e -> {
            collapsed[0] = toggled(collapsed[0]);
            view.setVisible(!collapsed[0]);
            usageExpanded.set(!collapsed[0]);
            var nowCollapsed = collapsed[0];
            Dialogs.logFailure(() -> AppSettings.shared().setBoolean(USAGE_COLLAPSED_KEY, nowCollapsed));
            refreshHeader.run();
        });
        usageView = new VBox(header, view);
        layoutFooter();
    }

    private void layoutFooter() {
        footer.getChildren().clear();
        if (usageView != null) {
            footer.getChildren().add(usageView);
        }
        if (actionsGrid != null) {
            footer.getChildren().add(actionsGrid);
        }
        setBottom(footer);
    }

    /** Shows the context usage under a session's row (FX thread). */
    public void setContext(String sessionId, String text) {
        contexts.put(sessionId, text);
        rebuild();
    }

    /** Drops the cached context text of a closed session. */
    public void forget(String sessionId) {
        if (sessionId != null) {
            contexts.remove(sessionId);
        }
    }

    private final javafx.animation.PauseTransition rebuildDelay = coalescer();

    private javafx.animation.PauseTransition coalescer() {
        var pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(100));
        pause.setOnFinished(e -> rebuildNow());
        return pause;
    }

    /** Requests a rebuild; bursts within ~100 ms collapse into one (FX thread). */
    private void rebuild() {
        rebuildDelay.playFromStart();
    }

    /** Rebuilds the Pinned and Projects sections from the tabs immediately (FX thread). */
    void rebuildNow() {
        rebuildDelay.stop();
        if (openMenu != null && openMenu.isShowing()) {
            return;
        }
        var all = snapshot(tabs);
        var groups = SidebarGroups.group(all, TerminalTab::directory, TerminalTab::pinned);
        content.getChildren().clear();
        if (!groups.pinned().isEmpty()) {
            content.getChildren().add(section("Pinned"));
            groups.pinned().forEach(tab -> content.getChildren().add(sessionRow(tab, true)));
        }
        if (!groups.projects().isEmpty()) {
            content.getChildren().add(section("Projects"));
        }
        for (var group : groups.projects()) {
            var folded = collapsed.contains(group.project());
            content.getChildren().add(projectRow(group, folded));
            if (!folded) {
                group.sessions().forEach(tab -> content.getChildren().add(sessionRow(tab, false)));
            }
        }
    }

    private static Label section(String text) {
        var label = new Label(text);
        label.getStyleClass().add("sidebar-section");
        return label;
    }

    private Node projectRow(SidebarGroups.Group<TerminalTab> group, boolean folded) {
        var chevron = new Label(folded ? "\u25B8" : "\u25BE");
        chevron.getStyleClass().add("project-chevron");
        chevron.setMinWidth(10);
        var icon = new Region();
        icon.getStyleClass().add("project-icon");
        icon.setShape(new SVGPath() {{ setContent(FOLDER); }});
        icon.setMinSize(14, 12);
        icon.setPrefSize(14, 12);
        icon.setMaxSize(14, 12);
        var name = new Label(group.name());
        name.getStyleClass().add("project-name");
        name.setMinWidth(0);
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var add = new Button("+");
        add.getStyleClass().add("sidebar-add");
        add.setFocusTraversable(false);
        add.setTooltip(new Tooltip("New session in " + group.project()));
        add.setOnAction(e -> {
            if (onNewInProject != null) {
                onNewInProject.accept(group.project());
            }
        });
        var row = new HBox(6, chevron, icon, name, spacer, add);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("project-row");
        add.visibleProperty().bind(row.hoverProperty());
        Tooltip.install(row, new Tooltip(group.project().toString()));
        row.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                if (!collapsed.remove(group.project())) {
                    collapsed.add(group.project());
                }
                rebuild();
            }
        });
        return row;
    }

    private Node sessionRow(TerminalTab tab, boolean showProject) {
        var dot = new Circle(4, switch (tab.cachedStatus()) {
            case TOOL_RUNNING -> runningColor(tab.activity());
            case SHELL -> GREY;
            case EXITED -> RED;
        });
        var dir = tab.directory();
        var named = !tab.name().isBlank();
        var name = new Label(named ? tab.name() : tab.tool().name());
        name.getStyleClass().add("session-name");
        name.setMinWidth(0);
        if (tab.unseen()) {
            name.getStyleClass().add("session-unseen");
        }
        var title = new HBox(6, name);
        title.setAlignment(Pos.CENTER_LEFT);
        if (tab.pinned()) {
            var pin = new Label("\uD83D\uDCCC");
            pin.getStyleClass().add("session-pin");
            title.getChildren().add(0, pin);
        }
        if (tab.unseen()) {
            var unread = new Circle(3, ACCENT);
            unread.getStyleClass().add("session-unread-dot");
            title.getChildren().add(unread);
        }
        var text = new VBox(1, title);
        if (showProject) {
            var project = new Label(dir.getFileName() == null ? "/" : dir.getFileName().toString());
            project.getStyleClass().add("session-path");
            text.getChildren().add(project);
        }
        java.util.Optional.ofNullable(tab.cachedBranch()).ifPresent(branch -> {
            var label = new Label("\u2387 " + branch);
            label.getStyleClass().add("session-branch");
            label.setCursor(Cursor.HAND);
            label.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    openBranches(tab, label);
                    e.consume();
                }
            });
            text.getChildren().add(label);
        });
        var activityText = tab.cachedStatus() == TerminalTab.Status.TOOL_RUNNING
                ? activityText(tab.activity()) : null;
        if (activityText != null) {
            var activityLabel = new Label(activityText);
            activityLabel.getStyleClass().add("session-activity");
            text.getChildren().add(activityLabel);
        }
        var ctx = contexts.get(tab.sessionId());
        if (ctx != null) {
            var ctxLabel = new Label(ctx);
            ctxLabel.getStyleClass().add("ctx-label");
            text.getChildren().add(ctxLabel);
        }
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        var row = new HBox(8, dot, text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("session-row");
        if (!showProject) {
            row.setPadding(new Insets(5, 8, 5, 26));
        }
        if (tabs.getSelectionModel().getSelectedItem() == tab) {
            row.getStyleClass().add("session-row-selected");
        }
        Tooltip.install(row, new Tooltip(dir.toString()));
        row.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                tabs.getSelectionModel().select(tab);
                if (e.getClickCount() == 2) {
                    rename(tab);
                }
            }
        });
        var menu = contextMenu(tab, row);
        menu.setOnHidden(e -> {
            openMenu = null;
            rebuild();
        });
        row.setOnContextMenuRequested(e -> {
            openMenu = menu;
            menu.show(row, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        return row;
    }

    private ContextMenu contextMenu(TerminalTab tab, Node anchor) {
        var pin = new MenuItem(tab.pinned() ? "Unpin" : "Pin");
        pin.setOnAction(e -> setPinned(tab, !tab.pinned()));
        var rename = new MenuItem("Rename\u2026");
        rename.setOnAction(e -> rename(tab));
        // No setAccelerator: a context menu registers it in the scene for this one row, and Ctrl+Shift+D would
        // then duplicate this tab instead of the selected one.
        var duplicate = new MenuItem("Duplicate tab");
        duplicate.setOnAction(e -> {
            if (onDuplicate != null) {
                onDuplicate.accept(tab);
            }
        });
        var switchBranch = new MenuItem("Switch branch\u2026");
        switchBranch.setOnAction(e -> openBranches(tab, anchor));
        var history = new MenuItem("History");
        history.setOnAction(e -> {
            if (onHistory != null) {
                onHistory.accept(tab);
            }
        });
        var close = new MenuItem("Close tab");
        close.setOnAction(e -> tab.requestClose());
        var items = new java.util.ArrayList<MenuItem>(List.of(pin, rename, duplicate, switchBranch, history));
        if (isClaude(tab)) {
            var fuse = new Menu("Fuse into");
            for (var target : fuseTargets(snapshot(tabs), tab, t -> t.alive() && isClaude(t))) {
                var item = new MenuItem(target.displayName());
                item.setOnAction(e -> {
                    if (onFuse != null) {
                        onFuse.accept(tab, target);
                    }
                });
                fuse.getItems().add(item);
            }
            fuse.setDisable(fuse.getItems().isEmpty());
            items.add(fuse);
        }
        items.add(new SeparatorMenuItem());
        items.add(close);
        return new ContextMenu(items.toArray(new MenuItem[0]));
    }

    private static boolean isClaude(TerminalTab tab) {
        return "claude".equals(tab.tool().name());
    }

    /** Tabs a session can be fused into: every eligible tab except the source itself. */
    static <T> List<T> fuseTargets(List<T> all, T source, java.util.function.Predicate<T> eligible) {
        return all.stream().filter(t -> t != source && eligible.test(t)).toList();
    }

    /** Status line for a reported activity; null when there is nothing to say. */
    static String activityText(TerminalTab.Activity activity) {
        if (activity == null) {
            return null;
        }
        return switch (activity) {
            case WAITING -> "waiting for you";
            case DONE -> "done";
            case ERROR -> "error";
            case WORKING -> null;
        };
    }

    /** Dot colour of a running tool: the reported activity, green while working or unknown. */
    public static Color runningColor(TerminalTab.Activity activity) {
        if (activity == null) {
            return GREEN;
        }
        return switch (activity) {
            case WAITING -> YELLOW;
            case DONE -> BLUE;
            case ERROR -> RED;
            case WORKING -> GREEN;
        };
    }

    /** Parent folder with the home directory as ~, e.g. ~/Documents/Tools. */
    static String parentLabel(Path dir, Path home) {
        var parent = dir.toAbsolutePath().getParent();
        return parent == null ? "" : homeLabel(parent, home);
    }

    /** The folder itself with the home directory as ~, e.g. ~/Documents/Tools/app. */
    static String homeLabel(Path dir, Path home) {
        var path = dir.toAbsolutePath();
        if (path.equals(home)) {
            return "~";
        }
        return path.startsWith(home) ? "~/" + home.relativize(path) : path.toString();
    }
}
