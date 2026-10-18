package dev.kamil.fabric.servers;

import dev.kamil.fabric.Dialogs;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Non-modal window over the fleet: servers on the left with a status dot, the apps of the selected server on the
 * right. Probes every server over ssh when shown and every 30 s, off the FX thread; Refresh polls at once.
 */
public final class ServersWindow {

    private static final int POLL_SECONDS = 30;
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static ServersWindow instance;

    private final Stage window = new Stage();
    private final Path inventory;
    private final Function<String, ServerProbe.Output> probe;
    private final Map<String, ServerStatus> statuses = new HashMap<>();
    private final Map<String, Instant> checkedAt = new HashMap<>();
    private final ListView<FleetInventory.Server> list = new ListView<>();
    private final TableView<FleetView.AppRow> table = new TableView<>();
    private final Button refresh = new Button("Refresh");
    private final Label empty = new Label();
    private final Label placeholder = new Label("Select a server");
    private final Label unreachable = new Label();
    private final Timeline ticker = new Timeline(new KeyFrame(javafx.util.Duration.seconds(POLL_SECONDS),
            e -> poll()));
    private final AtomicInteger threadNumber = new AtomicInteger();
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        var t = new Thread(r, "servers-probe-" + threadNumber.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private List<FleetInventory.Server> servers = List.of();
    private boolean polling;

    /** Opens the window, or brings the existing one to front. */
    public static void open(Window owner) {
        var probe = new ServerProbe();
        open(owner, FleetInventory.DEFAULT_PATH, probe::run);
    }

    /** The open window, or null; for tests. */
    static Stage openStage() {
        return instance == null ? null : instance.window;
    }

    /** Seam for tests: reads the inventory from {@code inventory} and probes through {@code probe}. */
    static void open(Window owner, Path inventory, Function<String, ServerProbe.Output> probe) {
        if (instance != null) {
            instance.window.show();
            instance.window.setIconified(false);
            instance.window.toFront();
            instance.poll();
            return;
        }
        var created = new ServersWindow(owner, inventory, probe);
        instance = created;
        created.window.setOnHidden(e -> {
            created.ticker.stop();
            created.executor.shutdownNow();
            instance = null;
        });
        created.window.show();
        created.poll();
        created.ticker.play();
    }

    private ServersWindow(Window owner, Path inventory, Function<String, ServerProbe.Output> probe) {
        this.inventory = inventory;
        this.probe = probe;
        window.initOwner(owner);
        window.setTitle("Servers");
        ticker.setCycleCount(Timeline.INDEFINITE);

        var path = new Label(inventory.toString());
        path.getStyleClass().add("history-meta");
        refresh.getStyleClass().add("primary");
        refresh.setOnAction(e -> poll());
        var bar = new HBox(10, refresh, path);
        bar.setAlignment(Pos.CENTER_LEFT);

        list.setPrefWidth(240);
        list.setCellFactory(v -> new ServerCell());
        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> showRows());

        placeholder.setWrapText(true);
        placeholder.setMaxWidth(420);
        placeholder.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        table.setPlaceholder(placeholder);
        table.getColumns().add(column("App", 180, ServersSummary::appName));
        table.getColumns().add(column("Env", 60, r -> r.env() == null ? "" : r.env()));
        table.getColumns().add(statusColumn());
        table.getColumns().add(column("Version", 90, r -> r.version() == null ? "" : r.version()));
        table.getColumns().add(column("Uptime", 90, r -> r.startedAt() == null ? ""
                : Uptime.format(Duration.between(r.startedAt(), Instant.now()))));
        table.getColumns().add(column("Health", 100, r -> r.health() == null ? "" : r.health()));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        unreachable.getStyleClass().add("inline-error");
        unreachable.setWrapText(true);
        unreachable.setVisible(false);
        unreachable.setManaged(false);
        VBox.setVgrow(table, Priority.ALWAYS);
        var tablePane = new VBox(6, unreachable, table);
        var split = new HBox(10, list, tablePane);
        HBox.setHgrow(tablePane, Priority.ALWAYS);
        VBox.setVgrow(split, Priority.ALWAYS);
        empty.getStyleClass().add("history-meta");
        empty.setWrapText(true);
        var root = new VBox(10, bar, empty, split, Dialogs.keyHint("Esc close"));
        root.setPadding(new Insets(14));
        var scene = new Scene(root, 900, 520);
        scene.getStylesheets().add(ServersWindow.class.getResource("/app.css").toExternalForm());
        window.setScene(scene);
        Dialogs.closeOnEscape(scene, window);
    }

    private static TableColumn<FleetView.AppRow, String> column(String title, double width,
            java.util.function.Function<FleetView.AppRow, String> text) {
        var col = new TableColumn<FleetView.AppRow, String>(title);
        col.setPrefWidth(width);
        col.setSortable(false);
        col.setCellValueFactory(c -> new ReadOnlyStringWrapper(text.apply(c.getValue())));
        return col;
    }

    private static TableColumn<FleetView.AppRow, String> statusColumn() {
        var col = column("Status", 100, r -> r.state().name());
        col.setCellFactory(c -> new TableCell<>() {
            private String colourStyle = "";

            {
                // the status colours are tuned for the dark panel; on a selected row the main text colour stays readable
                tableRowProperty().addListener((o, old, row) -> {
                    if (row != null) {
                        row.selectedProperty().addListener((obs, was, now) -> applyStyle());
                    }
                });
            }

            private void applyStyle() {
                var row = getTableRow();
                setStyle(row != null && row.isSelected() && !colourStyle.isEmpty()
                        ? "-fx-font-weight: bold;" : colourStyle);
            }

            @Override
            protected void updateItem(String item, boolean emptyCell) {
                super.updateItem(item, emptyCell);
                setText(emptyCell ? null : item);
                if (emptyCell || item == null) {
                    colourStyle = "";
                } else {
                    colourStyle = "-fx-text-fill: " + switch (AppState.valueOf(item)) {
                        case UP -> "#4caf50";
                        case DEGRADED -> "#ffb300";
                        case DOWN -> "#e5534b";
                        case UNKNOWN -> "#888888";
                    } + "; -fx-font-weight: bold;";
                }
                applyStyle();
            }
        });
        return col;
    }

    private static Color color(ServersSummary.Dot dot) {
        return switch (dot) {
            case GREEN -> Color.web("#4caf50");
            case AMBER -> Color.web("#ffb300");
            case RED -> Color.web("#e5534b");
            case GREY -> Color.web("#888888");
        };
    }

    private List<FleetView.AppRow> rowsOf(FleetInventory.Server server) {
        var status = statuses.get(server.alias());
        return status == null ? List.of() : FleetView.merge(server, status);
    }

    /** Reads the inventory and probes every server in parallel; ignored while a poll is in flight. */
    private void poll() {
        if (polling) {
            return;
        }
        polling = true;
        refresh.setDisable(true);
        var pool = executor;
        try {
            pool.execute(() -> {
                var found = FleetInventory.read(inventory);
                Platform.runLater(() -> setServers(found));
                var remaining = new AtomicInteger(found.size());
                if (found.isEmpty()) {
                    Platform.runLater(this::pollDone);
                }
                for (var server : found) {
                    pool.execute(() -> {
                        var status = ProbeParser.parse(server.alias(), probe.apply(server.alias()));
                        var now = Instant.now();
                        Platform.runLater(() -> {
                            statuses.put(server.alias(), status);
                            checkedAt.put(server.alias(), now);
                            list.refresh();
                            showRows();
                            if (remaining.decrementAndGet() == 0) {
                                pollDone();
                            }
                        });
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            polling = false; // the window was closed
        }
    }

    private void pollDone() {
        polling = false;
        refresh.setDisable(false);
    }

    private void setServers(List<FleetInventory.Server> found) {
        var selected = list.getSelectionModel().getSelectedItem();
        servers = found;
        list.setItems(FXCollections.observableArrayList(found));
        empty.setText(found.isEmpty() ? "No servers: no projects/*/fleet.env with SSH_HOST in "
                + inventory : "");
        empty.setManaged(found.isEmpty());
        empty.setVisible(found.isEmpty());
        found.stream().filter(s -> selected != null && s.alias().equals(selected.alias())).findFirst()
                .ifPresentOrElse(s -> list.getSelectionModel().select(s),
                        () -> list.getSelectionModel().selectFirst());
        showRows();
    }

    private void showRows() {
        var server = list.getSelectionModel().getSelectedItem();
        table.setItems(FXCollections.observableArrayList(server == null ? List.of() : rowsOf(server)));
        placeholder.setText(placeholderText(server));
        var status = server == null ? null : statuses.get(server.alias());
        var down = status != null && !status.reachable();
        unreachable.setText(down ? "Unreachable: " + status.error() : "");
        unreachable.setVisible(down);
        unreachable.setManaged(down);
    }

    /** Why the table is empty: nothing selected, the probe still running, or the server not answering. */
    private String placeholderText(FleetInventory.Server server) {
        if (server == null) {
            return "Select a server";
        }
        var status = statuses.get(server.alias());
        if (status == null) {
            return "Checking\u2026";
        }
        return status.reachable() ? "No apps" : "Unreachable: " + status.error();
    }

    private final class ServerCell extends ListCell<FleetInventory.Server> {
        @Override
        protected void updateItem(FleetInventory.Server server, boolean emptyCell) {
            super.updateItem(server, emptyCell);
            if (emptyCell || server == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            var status = statuses.get(server.alias());
            var dot = new Circle(6, color(ServersSummary.dot(status, rowsOf(server))));
            var name = new Label(server.alias());
            var metaText = ServersSummary.text(status, checkedAt.get(server.alias()), ZONE);
            var meta = new Label(metaText);
            meta.getStyleClass().add("history-meta");
            if (status != null && !status.reachable()) {
                // the cell is ~240 px wide: the full reason goes in a tooltip
                var reason = new Tooltip(metaText);
                reason.setWrapText(true);
                reason.setMaxWidth(420);
                meta.setTooltip(reason);
            }
            meta.setStyle("-fx-font-size: 11px;");
            var box = new HBox(8, new StackPane(dot), new VBox(2, name, meta));
            box.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(box);
        }
    }
}
