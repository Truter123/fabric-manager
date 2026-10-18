package dev.kamil.fabric.processes;

import dev.kamil.fabric.Dialogs;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Non-modal window listing docker containers and the developer's Java / Node processes. */
public final class ProcessesWindow {

    private static final long REFRESH_MILLIS = 3000;

    private static ProcessesWindow instance;

    private final Stage window = new Stage();
    private final BiConsumer<String, Path> runInTab;
    private final Docker docker;
    private final DevProcesses devProcesses;
    private final Signaler signaler;
    private final Path home = Path.of(System.getProperty("user.home"));

    private final TableView<DockerContainer> containerTable = new TableView<>();
    private final TableView<DevProcess> processTable = new TableView<>();
    private final CheckBox hideIde = new CheckBox("Hide IDE and MCP");
    private final TextField filter = new TextField();
    private final Label status = new Label();
    private final Label hidden = new Label();
    private final SystemStats systemStats;
    private final FlowPane statsBar = new FlowPane(18, 2);

    private List<DockerContainer> allContainers = List.of();
    private List<DevProcess> allProcesses = List.of();
    private String dockerError;
    private String actionError;
    private String busy;
    private volatile boolean running = true;
    private volatile boolean active = true;
    private final Object wakeLock = new Object();
    private boolean wakeNow;
    private Thread refreshThread;

    /** Opens the window, or brings the existing one to front. runInTab(command, dir): command null = plain shell. */
    public static void open(Window owner, BiConsumer<String, Path> runInTab) {
        open(owner, runInTab, new Docker(), new DevProcesses(), SystemStats.system(), ProcessesWindow::signal);
    }

    /** Sends stop (SIGTERM) or kill (SIGKILL) to a pid; returns an error message, or null on success. */
    interface Signaler {
        String send(long pid, boolean force);
    }

    /** The open window, or null; for tests. */
    static Stage openStage() {
        return instance == null ? null : instance.window;
    }

    /** Seam for tests: every external source and the process signalling are injected. */
    static void open(Window owner, BiConsumer<String, Path> runInTab, Docker docker, DevProcesses devProcesses,
            SystemStats systemStats, Signaler signaler) {
        if (instance != null) {
            instance.window.show();
            instance.window.setIconified(false);
            instance.window.toFront();
            instance.window.requestFocus();
            return;
        }
        var created = new ProcessesWindow(owner, runInTab, docker, devProcesses, systemStats, signaler);
        instance = created;
        created.window.setOnHidden(e -> {
            created.running = false;
            if (created.refreshThread != null) {
                created.refreshThread.interrupt();
            }
            instance = null;
        });
        created.window.show();
        created.startRefreshing();
    }

    private ProcessesWindow(Window owner, BiConsumer<String, Path> runInTab, Docker docker,
            DevProcesses devProcesses, SystemStats systemStats, Signaler signaler) {
        this.runInTab = runInTab;
        this.docker = docker;
        this.devProcesses = devProcesses;
        this.systemStats = systemStats;
        this.signaler = signaler;
        window.initOwner(owner);
        window.setTitle("Processes");
        // Mirrors of the window state for the refresh thread, maintained on the FX thread.
        window.focusedProperty().addListener((o, was, now) -> updateActive());
        window.iconifiedProperty().addListener((o, was, now) -> updateActive());

        hideIde.setSelected(true);
        hideIde.setOnAction(e -> applyFilter());
        filter.setPromptText("Filter by name or image (Ctrl+F)");
        filter.textProperty().addListener((o, a, b) -> applyFilter());
        HBox.setHgrow(filter, Priority.ALWAYS);
        var refresh = new Button("Refresh");
        refresh.setOnAction(e -> refreshAsync());
        hidden.getStyleClass().add("proc-stats-item");
        hidden.visibleProperty().bind(hidden.textProperty().isNotEmpty());
        hidden.managedProperty().bind(hidden.visibleProperty());
        var top = new HBox(8, filter, hideIde, hidden, refresh);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(8));

        buildContainerTable();
        buildProcessTable();

        var dockerButtons = new HBox(6,
                button("Start", () -> containerAction("start"), containerTable),
                button("Stop", () -> containerAction("stop"), containerTable),
                button("Restart", () -> containerAction("restart"), containerTable),
                button("Logs", this::logs, containerTable),
                button("Remove", this::removeContainer, containerTable));
        var processButtons = new HBox(6,
                button("Stop", () -> stopProcess(false), processTable),
                button("Kill", () -> stopProcess(true), processTable),
                button("Open folder in shell", this::openFolder, processTable));
        dockerButtons.setPadding(new Insets(6, 0, 0, 0));
        processButtons.setPadding(new Insets(6, 0, 0, 0));

        var dockerBox = new VBox(containerTable, dockerButtons);
        VBox.setVgrow(containerTable, Priority.ALWAYS);
        var processBox = new VBox(processTable, processButtons);
        VBox.setVgrow(processTable, Priority.ALWAYS);
        var dockerPane = titled("Docker", dockerBox);
        var processPane = titled("Java / Node", processBox);
        var split = new SplitPane(dockerPane, processPane);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.4);

        status.getStyleClass().add("history-meta");
        var statusBar = new HBox(status);
        statusBar.setPadding(new Insets(6, 8, 6, 8));
        var footer = new VBox(statusBar, Dialogs.keyHint("Ctrl+F filter   F5 refresh   Esc close"));

        var root = new BorderPane(split);
        statsBar.getStyleClass().add("proc-stats");
        statsBar.setPadding(new Insets(8, 8, 0, 8));
        root.setTop(new VBox(statsBar, top));
        root.setBottom(footer);
        var scene = new Scene(root, 1000, 600);
        scene.getStylesheets().add(ProcessesWindow.class.getResource("/app.css").toExternalForm());
        scene.getStylesheets().add(ProcessesWindow.class.getResource("/processes.css").toExternalForm());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN),
                () -> {
                    filter.requestFocus();
                    filter.selectAll();
                });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F5), this::refreshAsync);
        window.setScene(scene);
        Dialogs.closeOnEscape(scene, window);
    }

    private static TitledPane titled(String title, VBox content) {
        var pane = new TitledPane(title, content);
        pane.setCollapsible(false);
        return pane;
    }

    /** A button that acts on the selected row of {@code table}: disabled while nothing is selected. */
    private static Button button(String text, Runnable action, TableView<?> table) {
        var b = new Button(text);
        b.setOnAction(e -> action.run());
        b.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        return b;
    }

    private static <S> TableColumn<S, String> column(String title, double width, Function<S, String> value) {
        var col = new TableColumn<S, String>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(c -> new SimpleStringProperty(value.apply(c.getValue())));
        return col;
    }

    private void buildContainerTable() {
        var state = column("State", 80, (DockerContainer c) -> c.running() ? "running" : "exited");
        state.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("state-running", "state-exited");
                setText(empty ? null : item);
                if (!empty) {
                    getStyleClass().add("running".equals(item) ? "state-running" : "state-exited");
                }
            }
        });
        containerTable.getColumns().addAll(List.of(
                column("Name", 180, DockerContainer::name),
                column("Image", 220, DockerContainer::image),
                state,
                column("Status", 160, DockerContainer::status),
                column("Ports", 300, DockerContainer::ports)));
        containerTable.setPlaceholder(new Label("No containers"));
        containerTable.setContextMenu(new ContextMenu(
                item("Start", () -> containerAction("start")),
                item("Stop", () -> containerAction("stop")),
                item("Restart", () -> containerAction("restart")),
                item("Logs", this::logs),
                item("Remove", this::removeContainer)));
    }

    private void buildProcessTable() {
        processTable.getColumns().addAll(List.of(
                column("PID", 70, (DevProcess p) -> String.valueOf(p.pid())),
                column("Kind", 60, (DevProcess p) -> p.kind().name()),
                column("Name", 260, DevProcess::name),
                column("Folder", 300, p -> shortPath(p.cwd())),
                column("Ports", 120, p -> p.ports().stream().map(String::valueOf).collect(Collectors.joining(", "))),
                column("", 80, p -> p.inContainer() ? "container" : "")));
        processTable.setPlaceholder(new Label("No processes"));
        processTable.setContextMenu(new ContextMenu(
                item("Stop", () -> stopProcess(false)),
                item("Kill", () -> stopProcess(true)),
                item("Open folder in shell", this::openFolder)));
    }

    private static MenuItem item(String text, Runnable action) {
        var item = new MenuItem(text);
        item.setOnAction(e -> action.run());
        return item;
    }

    private String shortPath(String cwd) {
        var h = home.toString();
        if (cwd.equals(h)) {
            return "~";
        }
        return cwd.startsWith(h + "/") ? "~" + cwd.substring(h.length()) : cwd;
    }

    private void updateActive() {
        boolean now = window.isFocused() && !window.isIconified();
        boolean was = active;
        active = now;
        if (now && !was) {
            synchronized (wakeLock) {
                wakeNow = true;
                wakeLock.notifyAll();
            }
        }
    }

    private void startRefreshing() {
        var thread = new Thread(() -> {
            while (running) {
                if (active) {
                    loadStats();
                    load();
                }
                try {
                    synchronized (wakeLock) {
                        if (!wakeNow) {
                            wakeLock.wait(REFRESH_MILLIS);
                        }
                        wakeNow = false;
                    }
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "processes-refresh");
        thread.setDaemon(true);
        refreshThread = thread;
        thread.start();
    }

    private void refreshAsync() {
        var thread = new Thread(this::load, "processes-refresh-now");
        thread.setDaemon(true);
        thread.start();
    }

    private void loadStats() {
        var parts = systemStats.sample();
        Platform.runLater(() -> {
            if (!running) {
                return;
            }
            statsBar.getChildren().setAll(parts.stream().map(part -> {
                var label = new Label(part.text());
                label.getStyleClass().add("proc-stats-item");
                if (!part.level().isEmpty()) {
                    label.getStyleClass().add("proc-stats-" + part.level());
                }
                return label;
            }).toList());
        });
    }

    private void load() {
        var listing = docker.list();
        var processes = devProcesses.list();
        Platform.runLater(() -> {
            if (!running) {
                return;
            }
            allContainers = listing.containers();
            dockerError = listing.error();
            allProcesses = processes;
            applyFilter();
        });
    }

    private void applyFilter() {
        var needle = filter.getText().strip().toLowerCase(Locale.ROOT);
        boolean hide = hideIde.isSelected();
        var containers = allContainers.stream()
                .filter(c -> needle.isEmpty() || c.name().toLowerCase(Locale.ROOT).contains(needle)
                        || c.image().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        var matching = allProcesses.stream()
                .filter(p -> needle.isEmpty() || p.name().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        var processes = matching.stream().filter(p -> !hide || !isIdeOrMcp(p)).toList();
        int hiddenCount = matching.size() - processes.size();
        hidden.setText(hiddenCount == 0 ? "" : hiddenCount + " hidden");

        var selectedContainer = containerTable.getSelectionModel().getSelectedItem();
        var selectedProcess = processTable.getSelectionModel().getSelectedItem();
        containerTable.setItems(FXCollections.observableArrayList(containers));
        processTable.setItems(FXCollections.observableArrayList(processes));
        if (selectedContainer != null) {
            containers.stream().filter(c -> c.id().equals(selectedContainer.id())).findFirst()
                    .ifPresent(containerTable.getSelectionModel()::select);
        }
        if (selectedProcess != null) {
            processes.stream().filter(p -> p.pid() == selectedProcess.pid()).findFirst()
                    .ifPresent(processTable.getSelectionModel()::select);
        }
        var error = actionError != null ? actionError : dockerError;
        status.setText(busy != null ? busy
                : error != null ? error : containers.size() + " containers, " + processes.size() + " processes");
    }

    static boolean isIdeOrMcp(DevProcess p) {
        var line = p.commandLine().toLowerCase(Locale.ROOT);
        return line.contains("/idea-") || line.contains("jetbrains") || line.contains("mcp");
    }

    private void containerAction(String verb) {
        var c = containerTable.getSelectionModel().getSelectedItem();
        if (c == null) {
            return;
        }
        runDocker(verb, c);
    }

    private static String busyText(String verb, String name) {
        return switch (verb) {
            case "start" -> "Starting ";
            case "stop" -> "Stopping ";
            case "restart" -> "Restarting ";
            default -> "Removing ";
        } + name + "\u2026";
    }

    private void runDocker(String verb, DockerContainer c) {
        var id = c.id();
        busy = busyText(verb, c.name());
        status.setText(busy);
        var thread = new Thread(() -> {
            var error = docker.action(verb, id);
            Platform.runLater(() -> {
                busy = null;
                actionError = error;
            });
            load();
        }, "docker-" + verb);
        thread.setDaemon(true);
        thread.start();
    }

    private void removeContainer() {
        var c = containerTable.getSelectionModel().getSelectedItem();
        if (c == null) {
            return;
        }
        if (Dialogs.confirm(window, "Remove container " + c.name() + "?",
                "The container " + c.name() + " will be deleted.", "Remove")) {
            runDocker("rm", c);
        }
    }

    private void logs() {
        var c = containerTable.getSelectionModel().getSelectedItem();
        if (c != null && runInTab != null) {
            runInTab.accept("docker logs -f --tail 200 " + c.name(), home);
        }
    }

    private void stopProcess(boolean force) {
        var p = processTable.getSelectionModel().getSelectedItem();
        if (p == null) {
            return;
        }
        var verb = force ? "Kill" : "Stop";
        if (!Dialogs.confirm(window, verb + " process " + p.pid() + "?", p.name(), verb)) {
            return;
        }
        actionError = signaler.send(p.pid(), force);
        refreshAsync();
        applyFilter();
    }

    private static String signal(long pid, boolean force) {
        var verb = force ? "Kill" : "Stop";
        var handle = ProcessHandle.of(pid);
        if (handle.isEmpty()) {
            return "Process " + pid + " is gone";
        }
        boolean ok = force ? handle.get().destroyForcibly() : handle.get().destroy();
        return ok ? null : verb + " failed for pid " + pid;
    }

    private void openFolder() {
        var p = processTable.getSelectionModel().getSelectedItem();
        if (p != null && runInTab != null && !p.cwd().isEmpty()) {
            runInTab.accept(null, Path.of(p.cwd()));
        }
    }
}
