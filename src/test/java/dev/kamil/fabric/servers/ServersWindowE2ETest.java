package dev.kamil.fabric.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.EscapeClosesWindowsE2ETest;
import dev.kamil.fabric.FxHarness;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.Arguments;

/** Drives the real Servers window with a temporary inventory and a stubbed probe; no ssh, no network. */
class ServersWindowE2ETest {

    private static final String SHA = "46771ef8f3cdbc0eb1fc3370deae7d7ef5ce925a";
    private static final List<Throwable> UNCAUGHT = Collections.synchronizedList(new ArrayList<>());

    @TempDir
    Path center;

    private final Map<String, AtomicInteger> probed = new ConcurrentHashMap<>();
    private volatile CountDownLatch gate = new CountDownLatch(0);

    @BeforeAll
    static void startFx() {
        Assumptions.assumeTrue(FxHarness.displayAvailable(),
                "no display");
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // another test class started it
        }
        Platform.setImplicitExit(false);
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> UNCAUGHT.add(e));
    }

    @BeforeEach
    void clear() {
        UNCAUGHT.clear();
    }

    @AfterEach
    void close() throws Exception {
        fx(() -> {
            var stage = ServersWindow.openStage();
            if (stage != null) {
                stage.hide();
            }
            return null;
        });
        assertTrue(UNCAUGHT.isEmpty(), "uncaught exceptions: " + UNCAUGHT);
    }

    // ---- fixtures

    private void project(String name, String host, String dir, String testDir) throws IOException {
        var folder = Files.createDirectories(center.resolve("projects").resolve(name));
        Files.writeString(folder.resolve("fleet.env"), "SSH_HOST=\"" + host + "\"\nREMOTE_DIR=\"" + dir + "\"\n"
                + (testDir == null ? "" : "REMOTE_DIR_TEST=\"" + testDir + "\"\n"));
    }

    /** alpha has a healthy prod stack and no test stack; beta cannot be reached. */
    private ServerProbe.Output probe(String alias) {
        probed.computeIfAbsent(alias, k -> new AtomicInteger()).incrementAndGet();
        try {
            gate.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (alias.equals("beta")) {
            return new ServerProbe.Output("", 255, "Connection refused");
        }
        var ls = "[{\"Name\":\"a-app\",\"Status\":\"running(1)\",\"ConfigFiles\":\"/home/admin/a-dir/docker-compose.yml\"}]";
        var inspect = "\"/a-app-backend\" \"running\" \"2026-10-11T15:34:51.369897Z\" \"healthy\" \"kamil/a:backend-"
                + SHA + "\" \"a-app\"";
        return new ServerProbe.Output(ls + "\n---\n" + inspect + "\n---\na-dir " + SHA + "\n", 0, "");
    }

    private void openWindow() throws Exception {
        fx(() -> {
            ServersWindow.open(null, center, this::probe);
            return null;
        });
    }

    private void openFleet() throws Exception {
        project("a", "alpha", "a-dir", "a-test");
        project("b", "beta", "b-dir", null);
        openWindow();
        await(() -> serverCells().size() == 2 && !refreshButton().isDisabled());
    }

    /** The case of {@link EscapeClosesWindowsE2ETest}: an empty fleet, so nothing is probed. */
    static Stream<Arguments> escapeCase() {
        return Stream.of(EscapeClosesWindowsE2ETest.escapeCase("Servers", dir -> {
            Files.createDirectories(dir.resolve("projects"));
            return FxHarness.fx(() -> {
                ServersWindow.open(null, dir, alias -> new ServerProbe.Output("", 255, "unreachable"));
                return ServersWindow.openStage();
            });
        }, () -> FxHarness.fx(() -> {
            var stage = ServersWindow.openStage();
            if (stage != null) {
                stage.hide();
            }
            return null;
        })));
    }

    // ---- harness

    private static <T> T fx(Callable<T> action) throws Exception {
        var future = new CompletableFuture<T>();
        Platform.runLater(() -> {
            try {
                future.complete(action.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.get(10, TimeUnit.SECONDS);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            if (fx(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("condition not reached");
    }

    private static Node root() {
        return ServersWindow.openStage().getScene().getRoot();
    }

    @SuppressWarnings("unchecked")
    private static ListView<FleetInventory.Server> list() {
        return (ListView<FleetInventory.Server>) root().lookup(".list-view");
    }

    @SuppressWarnings("unchecked")
    private static TableView<FleetView.AppRow> table() {
        return (TableView<FleetView.AppRow>) root().lookup(".table-view");
    }

    private static Button refreshButton() {
        return (Button) root().lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && "Refresh".equals(b.getText())).findFirst().orElseThrow();
    }

    /** Rendered rows of the server list, in order: {alias, meta text, dot colour}. Call on the FX thread. */
    private static List<List<Object>> serverCells() {
        var out = new ArrayList<List<Object>>();
        for (var node : list().lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> cell && !cell.isEmpty() && cell.getGraphic() instanceof HBox box) {
                var circle = (Circle) ((javafx.scene.layout.StackPane) box.getChildren().get(0)).getChildren().get(0);
                var texts = (VBox) box.getChildren().get(1);
                out.add(List.of(((Label) texts.getChildren().get(0)).getText(),
                        ((Label) texts.getChildren().get(1)).getText(), circle.getFill()));
            }
        }
        out.sort((a, b) -> ((String) a.get(0)).compareTo((String) b.get(0)));
        return out;
    }

    /** Rendered text of the table cells per row (6 columns), skipping empty padding rows. */
    private static List<List<String>> tableTexts() {
        var out = new ArrayList<List<String>>();
        for (var row : table().lookupAll(".table-row-cell")) {
            var cells = row.lookupAll(".table-cell").stream().filter(n -> n instanceof TableCell<?, ?> c && !c.isEmpty())
                    .map(n -> ((TableCell<?, ?>) n).getText()).toList();
            if (!cells.isEmpty()) {
                out.add(cells);
            }
        }
        return out;
    }

    private static void select(String alias) throws Exception {
        fx(() -> {
            list().getSelectionModel().select(list().getItems().stream().filter(s -> s.alias().equals(alias))
                    .findFirst().orElseThrow());
            return null;
        });
    }

    // ---- tests

    @Test
    void listsEveryServerWithItsProbeResult() throws Exception {
        openFleet();
        var cells = fx(ServersWindowE2ETest::serverCells);
        assertEquals("alpha", cells.get(0).get(0));
        assertTrue(((String) cells.get(0).get(1)).startsWith("checked "), cells.get(0).get(1).toString());
        assertEquals(Color.web("#e5534b"), cells.get(0).get(2), "alpha misses its test stack: red");
        assertEquals("beta", cells.get(1).get(0));
        assertEquals("unreachable: Connection refused", cells.get(1).get(1));
        assertEquals(Color.web("#e5534b"), cells.get(1).get(2));
        assertEquals(1, probed.get("alpha").get());
        assertEquals(1, probed.get("beta").get());
    }

    @Test
    void firstServerIsSelectedAndItsAppsShown() throws Exception {
        openFleet();
        assertEquals("alpha", fx(() -> list().getSelectionModel().getSelectedItem().alias()));
        await(() -> tableTexts().size() == 2);
        var rows = fx(ServersWindowE2ETest::tableTexts);
        assertEquals(List.of("a", "prod", "UP", "46771ef"), rows.get(0).subList(0, 4));
        assertEquals("healthy", rows.get(0).get(5));
        assertFalse(rows.get(0).get(4).isEmpty(), "uptime");
        assertEquals(List.of("a", "test", "DOWN", "", "", ""), rows.get(1));
    }

    @Test
    void statusColumnIsColouredPerState() throws Exception {
        openFleet();
        await(() -> tableTexts().size() == 2);
        var styles = fx(() -> table().lookupAll(".table-cell").stream()
                .filter(n -> n instanceof TableCell<?, ?> c && !c.isEmpty() && ("UP".equals(c.getText())
                        || "DOWN".equals(c.getText()))).map(n -> ((TableCell<?, ?>) n).getText() + ":" + n.getStyle())
                .toList());
        assertTrue(styles.stream().anyMatch(s -> s.startsWith("UP:") && s.contains("#4caf50")), styles.toString());
        assertTrue(styles.stream().anyMatch(s -> s.startsWith("DOWN:") && s.contains("#e5534b")), styles.toString());
    }

    @Test
    void selectingAnUnreachableServerShowsUnknownRows() throws Exception {
        openFleet();
        select("beta");
        await(() -> tableTexts().size() == 1);
        assertEquals(List.of("b", "prod", "UNKNOWN", "", "", ""), fx(ServersWindowE2ETest::tableTexts).get(0));
        select("alpha");
        await(() -> tableTexts().size() == 2);
    }

    @Test
    void refreshProbesAgainAndIsDisabledWhileInFlight() throws Exception {
        openFleet();
        gate = new CountDownLatch(1);
        fx(() -> {
            refreshButton().fire();
            return null;
        });
        assertTrue(fx(() -> refreshButton().isDisabled()));
        fx(() -> {
            refreshButton().fire(); // ignored while polling
            return null;
        });
        gate.countDown();
        await(() -> !refreshButton().isDisabled());
        assertEquals(2, probed.get("alpha").get());
        assertEquals(2, probed.get("beta").get());
    }

    @Test
    void repeatedRefreshesReuseCellsWithoutErrors() throws Exception {
        openFleet();
        for (var i = 0; i < 4; i++) {
            fx(() -> {
                refreshButton().fire();
                return null;
            });
            var expected = 2 + i;
            await(() -> !refreshButton().isDisabled() && probed.get("alpha").get() == expected);
        }
        assertEquals(2, fx(ServersWindowE2ETest::serverCells).size());
        await(() -> tableTexts().size() == 2);
    }

    @Test
    void newServerAppearsOnRefreshAndSelectionIsKept() throws Exception {
        openFleet();
        select("beta");
        project("c", "gamma", "c-dir", null);
        fx(() -> {
            refreshButton().fire();
            return null;
        });
        await(() -> serverCells().size() == 3 && !refreshButton().isDisabled());
        assertEquals("beta", fx(() -> list().getSelectionModel().getSelectedItem().alias()));
    }

    @Test
    void emptyInventoryShowsHintAndPlaceholder() throws Exception {
        openWindow();
        await(() -> root().lookupAll(".label").stream().anyMatch(n -> n instanceof Label l && l.isVisible()
                && l.getText().startsWith("No servers: no projects/*/fleet.env with SSH_HOST in " + center)));
        assertTrue(fx(() -> list().getItems().isEmpty()));
        assertTrue(fx(() -> table().getItems().isEmpty()));
        await(() -> !refreshButton().isDisabled());
        assertTrue(probed.isEmpty());
    }

    @Test
    void openingAgainReusesTheWindowAndPollsAgain() throws Exception {
        openFleet();
        var first = fx(ServersWindow::openStage);
        assertNotNull(first);
        openWindow();
        assertSame(first, fx(ServersWindow::openStage));
        await(() -> probed.get("alpha").get() == 2 && !refreshButton().isDisabled());
    }

    @Test
    void closingTheWindowForgetsIt() throws Exception {
        openFleet();
        fx(() -> {
            ServersWindow.openStage().hide();
            return null;
        });
        assertNull(fx(ServersWindow::openStage));
        openFleet();
        assertNotNull(fx(ServersWindow::openStage));
    }

    private static String placeholder() {
        return ((Label) table().getPlaceholder()).getText();
    }

    @Test
    void placeholderSaysCheckingThenWhyAServerHasNoApps() throws Exception {
        project("a", "alpha", "a-dir", "a-test");
        project("b", "beta", "b-dir", null);
        gate = new CountDownLatch(1);
        openWindow();
        await(() -> list().getItems().size() == 2);
        assertEquals("Checking\u2026", fx(ServersWindowE2ETest::placeholder));
        gate.countDown();
        await(() -> serverCells().size() == 2 && !refreshButton().isDisabled());
        select("beta");
        await(() -> "Unreachable: Connection refused".equals(placeholder()));
    }

    @Test
    void anUnreachableServerShowsItsReasonAboveTheTableWhileKeepingTheUnknownRows() throws Exception {
        openFleet();
        select("alpha");
        await(() -> tableTexts().size() == 2);
        var reason = fx(() -> (Label) root().lookup(".inline-error"));
        assertFalse(fx(() -> reason.isVisible() || reason.isManaged()), "a reachable server shows no error");
        select("beta");
        await(() -> reason.isVisible());
        assertEquals("Unreachable: Connection refused", fx(reason::getText));
        assertTrue(fx(reason::isManaged));
        await(() -> tableTexts().size() == 1); // the UNKNOWN row stays
        assertEquals("UNKNOWN", fx(ServersWindowE2ETest::tableTexts).get(0).get(2));
        assertTrue(fx(() -> reason.getParent() == table().getParent()), "the reason sits above the table");
        select("alpha");
        await(() -> !reason.isVisible());
        assertFalse(fx(reason::isManaged));
    }

    @Test
    void placeholderAsksForAServerOnlyWhenNothingIsSelected() throws Exception {
        openWindow();
        await(() -> !refreshButton().isDisabled());
        assertEquals("Select a server", fx(ServersWindowE2ETest::placeholder));
    }

    @Test
    void unreachableReasonIsInTheSidebarTooltip() throws Exception {
        openFleet();
        var tips = fx(() -> list().lookupAll(".list-cell").stream()
                .filter(n -> n instanceof ListCell<?> c && !c.isEmpty() && c.getGraphic() instanceof HBox)
                .map(n -> (Label) ((VBox) ((HBox) ((ListCell<?>) n).getGraphic()).getChildren().get(1))
                        .getChildren().get(1))
                .filter(l -> l.getTooltip() != null).map(l -> l.getTooltip().getText()).toList());
        assertEquals(List.of("unreachable: Connection refused"), tips);
    }

    @Test
    void footerHintListsTheKeysAndEscCloses() throws Exception {
        openWindow();
        var hint = fx(() -> root().lookupAll(".key-hint").stream().map(n -> ((Label) n).getText()).toList());
        assertEquals(1, hint.size());
        assertTrue(hint.get(0).contains("Esc close"), hint.get(0));
        var primary = fx(() -> root().lookupAll(".button.primary").stream().map(n -> ((Button) n).getText()).toList());
        assertEquals(1, primary.size(), primary.toString());
        assertTrue(primary.get(0).startsWith("Refresh"), primary.get(0));
    }
}
