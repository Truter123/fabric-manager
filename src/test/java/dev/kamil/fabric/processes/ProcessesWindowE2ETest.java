package dev.kamil.fabric.processes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.EscapeClosesWindowsE2ETest;
import dev.kamil.fabric.FxHarness;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;

/**
 * Drives the real Processes window. Docker, process listing, system stats and the stop/kill signals are all
 * stubbed: no real docker command runs and no real process is ever signalled.
 */
class ProcessesWindowE2ETest {

    private static final String HOME = System.getProperty("user.home");
    private static final List<Throwable> UNCAUGHT = Collections.synchronizedList(new ArrayList<>());

    private static final DockerContainer WEB = new DockerContainer("id-web", "web", "nginx:1", "Up 2 hours",
            "0.0.0.0:80->80/tcp", true);
    private static final DockerContainer DB = new DockerContainer("id-db", "postgres-db", "postgres:16",
            "Exited (0) 1 hour ago", "", false);
    private static final DevProcess APP = new DevProcess(4242, DevProcess.Kind.JAVA, "my-service.jar",
            HOME + "/work/app", List.of(8080, 9090), false, "java -jar my-service.jar");
    private static final DevProcess VITE = new DevProcess(4343, DevProcess.Kind.NODE, "vite", "/srv/site", List.of(),
            true, "node vite");
    private static final DevProcess IDE = new DevProcess(4444, DevProcess.Kind.JAVA, "idea", HOME,
            List.of(), false, "/opt/idea-IU/bin/idea");

    private static final class StubDocker extends Docker {
        volatile Listing listing = new Listing(List.of(WEB, DB), null);
        final List<String> actions = new CopyOnWriteArrayList<>();
        volatile String actionResult;
        volatile int lists;
        volatile CountDownLatch actionGate = new CountDownLatch(0);

        @Override
        public Listing list() {
            lists++;
            return listing;
        }

        @Override
        public String action(String verb, String id) {
            actions.add(verb + " " + id);
            try {
                actionGate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return actionResult;
        }
    }

    private static final class StubProcesses extends DevProcesses {
        volatile List<DevProcess> processes = List.of(APP, VITE, IDE);

        @Override
        public List<DevProcess> list() {
            return processes;
        }
    }

    private static final class StubStats extends SystemStats {
        StubStats() {
            super(Path.of("/nonexistent-sys"), Path.of("/nonexistent-proc"));
        }

        @Override
        public List<Part> sample() {
            return List.of(new Part("CPU 12%", ""), new Part("GPU 85°C", "warn"), new Part("NVMe 95°C", "hot"));
        }
    }

    private final StubDocker docker = new StubDocker();
    private final StubProcesses processes = new StubProcesses();
    private final List<String> signals = new CopyOnWriteArrayList<>();
    private final List<String> tabs = new CopyOnWriteArrayList<>();

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
    void open() throws Exception {
        UNCAUGHT.clear();
        fx(() -> {
            ProcessesWindow.open(null, (command, dir) -> tabs.add(command + " @ " + dir), docker, processes,
                    new StubStats(), (pid, force) -> {
                        signals.add((force ? "kill " : "stop ") + pid);
                        return null;
                    });
            return null;
        });
        await(() -> containerTable().getItems().size() == 2);
    }

    @AfterEach
    void close() throws Exception {
        fx(() -> {
            var stage = ProcessesWindow.openStage();
            if (stage != null) {
                stage.hide();
            }
            return null;
        });
        assertTrue(UNCAUGHT.isEmpty(), "uncaught exceptions: " + UNCAUGHT);
    }

    /** The case of {@link EscapeClosesWindowsE2ETest}: the same stubs as the other tests, nothing real is listed. */
    static Stream<Arguments> escapeCase() {
        return Stream.of(EscapeClosesWindowsE2ETest.escapeCase("Processes", dir -> {
            var docker = new StubDocker();
            var stage = FxHarness.fx(() -> {
                ProcessesWindow.open(null, (command, where) -> { }, docker, new StubProcesses(), new StubStats(),
                        (pid, force) -> null);
                return ProcessesWindow.openStage();
            });
            FxHarness.await(() -> docker.lists > 0);
            FxHarness.fx(() -> null);
            return stage;
        }, () -> FxHarness.fx(() -> {
            var stage = ProcessesWindow.openStage();
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

    private static javafx.scene.Parent root() {
        return ProcessesWindow.openStage().getScene().getRoot();
    }

    @SuppressWarnings("unchecked")
    private static TableView<DockerContainer> containerTable() {
        return (TableView<DockerContainer>) root().lookupAll(".table-view").toArray()[0];
    }

    @SuppressWarnings("unchecked")
    private static TableView<DevProcess> processTable() {
        return (TableView<DevProcess>) root().lookupAll(".table-view").toArray()[1];
    }

    /** The button row under a table (both tables sit in a VBox [table, buttons]). */
    private static Button button(TableView<?> table, String text) {
        var row = (HBox) ((VBox) table.getParent()).getChildren().get(1);
        return (Button) row.getChildren().stream().filter(n -> n instanceof Button b && text.equals(b.getText()))
                .findFirst().orElseThrow();
    }

    private static Button toolbarButton(String text) {
        return (Button) root().lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && text.equals(b.getText()) && b.getParent() instanceof HBox h
                        && h.getChildren().stream().anyMatch(c -> c instanceof TextField))
                .findFirst().orElseThrow();
    }

    private static TextField filter() {
        return (TextField) root().lookup(".text-field");
    }

    private static CheckBox hideIde() {
        return (CheckBox) root().lookup(".check-box");
    }

    private static String statusText() {
        return ((Label) root().lookupAll(".history-meta").toArray()[0]).getText();
    }

    private static void fire(Button b) throws Exception {
        Platform.runLater(b::fire);
        fx(() -> null);
    }

    private static void press(KeyCode code, boolean ctrl) throws Exception {
        fx(() -> {
            Event.fireEvent(root(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl, false, false));
            return null;
        });
    }

    private static List<List<String>> cellTexts(TableView<?> table) {
        var out = new ArrayList<List<String>>();
        for (var row : table.lookupAll(".table-row-cell")) {
            var cells = row.lookupAll(".table-cell").stream()
                    .filter(n -> n instanceof TableCell<?, ?> c && !c.isEmpty())
                    .map(n -> {
                        var text = ((TableCell<?, ?>) n).getText();
                        return text == null ? "" : text;
                    }).toList();
            if (!cells.isEmpty()) {
                out.add(cells);
            }
        }
        return out;
    }

    private static DialogPane dialog() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w.getScene() != null && w.getScene().getRoot() instanceof DialogPane p) {
                return p;
            }
        }
        return null;
    }

    private static void answer(String buttonText) throws Exception {
        await(() -> dialog() != null);
        fx(() -> {
            var pane = dialog();
            var type = pane.getButtonTypes().stream().filter(t -> t.getText().equals(buttonText)).findFirst()
                    .orElseThrow();
            ((Button) pane.lookupButton(type)).fire();
            return null;
        });
        await(() -> dialog() == null);
    }

    private void selectContainer(DockerContainer c) throws Exception {
        fx(() -> {
            containerTable().getSelectionModel().select(c);
            return null;
        });
    }

    private void selectProcess(DevProcess p) throws Exception {
        fx(() -> {
            processTable().getSelectionModel().select(p);
            return null;
        });
    }

    // ---- tests

    @Test
    void showsContainersAndProcessesWithIdeHiddenByDefault() throws Exception {
        await(() -> processTable().getItems().size() == 2);
        await(() -> cellTexts(containerTable()).size() == 2 && cellTexts(processTable()).size() == 2);
        var containers = fx(() -> cellTexts(containerTable()));
        assertEquals(List.of("web", "nginx:1", "running", "Up 2 hours", "0.0.0.0:80->80/tcp"), containers.get(0));
        assertEquals(List.of("postgres-db", "postgres:16", "exited", "Exited (0) 1 hour ago", ""), containers.get(1));
        var procs = fx(() -> cellTexts(processTable()));
        assertEquals(List.of("4242", "JAVA", "my-service.jar", "~/work/app", "8080, 9090", ""), procs.get(0));
        assertEquals(List.of("4343", "NODE", "vite", "/srv/site", "", "container"), procs.get(1));
        assertEquals("2 containers, 2 processes", fx(ProcessesWindowE2ETest::statusText));
        assertTrue(fx(() -> hideIde().isSelected()));
    }

    @Test
    void hideIdeCheckboxTogglesTheIdeProcesses() throws Exception {
        await(() -> processTable().getItems().size() == 2);
        fx(() -> {
            hideIde().fire();
            return null;
        });
        assertEquals(3, fx(() -> processTable().getItems().size()));
        assertEquals("2 containers, 3 processes", fx(ProcessesWindowE2ETest::statusText));
        fx(() -> {
            hideIde().fire();
            return null;
        });
        assertEquals(2, fx(() -> processTable().getItems().size()));
    }

    @Test
    void stateCellsCarryTheirStyleClassAcrossFilterChanges() throws Exception {
        await(() -> cellTexts(containerTable()).size() == 2);
        for (var round = 0; round < 3; round++) {
            var text = round % 2 == 0 ? "postgres" : "";
            fx(() -> {
                filter().setText(text);
                return null;
            });
            fx(() -> null);
        }
        fx(() -> {
            filter().setText("");
            return null;
        });
        await(() -> cellTexts(containerTable()).size() == 2);
        var classes = fx(() -> containerTable().lookupAll(".table-cell").stream()
                .filter(n -> n instanceof TableCell<?, ?> c && !c.isEmpty()
                        && ("running".equals(c.getText()) || "exited".equals(c.getText())))
                .map(n -> ((TableCell<?, ?>) n).getText() + ":" + n.getStyleClass().contains("state-running")
                        + n.getStyleClass().contains("state-exited"))
                .sorted().toList());
        assertEquals(List.of("exited:falsetrue", "running:truefalse"), classes);
    }

    @Test
    void filterMatchesNameOrImageForContainersAndNameForProcesses() throws Exception {
        fx(() -> {
            filter().setText("PG");
            return null;
        });
        assertEquals(0, fx(() -> containerTable().getItems().size()));
        fx(() -> {
            filter().setText("postgres:16");
            return null;
        });
        assertEquals(List.of("postgres-db"), fx(() -> containerTable().getItems().stream()
                .map(DockerContainer::name).toList()));
        fx(() -> {
            filter().setText("vite");
            return null;
        });
        assertEquals(List.of(4343L), fx(() -> processTable().getItems().stream().map(DevProcess::pid).toList()));
        assertEquals("0 containers, 1 processes", fx(ProcessesWindowE2ETest::statusText));
        fx(() -> {
            filter().clear();
            return null;
        });
        assertEquals(2, fx(() -> containerTable().getItems().size()));
    }

    @Test
    void ctrlFFocusesTheFilter() throws Exception {
        press(KeyCode.F, true);
        await(() -> filter().isFocused());
    }

    @Test
    void refreshButtonAndF5ReloadTheListings() throws Exception {
        var before = docker.lists;
        fire(toolbarButtonOnFx("Refresh"));
        await(() -> docker.lists > before);
        var mid = docker.lists;
        docker.listing = new Docker.Listing(List.of(WEB), null);
        press(KeyCode.F5, false);
        await(() -> containerTable().getItems().size() == 1);
        assertTrue(docker.lists > mid);
    }

    private static Button toolbarButtonOnFx(String text) throws Exception {
        return fx(() -> toolbarButton(text));
    }

    @Test
    void dockerErrorIsShownInTheStatusBar() throws Exception {
        docker.listing = new Docker.Listing(List.of(), "docker not available: boom");
        fire(toolbarButtonOnFx("Refresh"));
        await(() -> "docker not available: boom".equals(statusText()));
        assertEquals(0, fx(() -> containerTable().getItems().size()));
    }

    @Test
    void statsBarShowsEverySampledPartWithItsLevel() throws Exception {
        await(() -> ((FlowPane) root().lookup(".proc-stats")).getChildren().size() == 3);
        var labels = fx(() -> ((FlowPane) root().lookup(".proc-stats")).getChildren().stream()
                .map(n -> ((Label) n).getText() + ":" + n.getStyleClass().contains("proc-stats-warn")
                        + n.getStyleClass().contains("proc-stats-hot")).toList());
        assertEquals(List.of("CPU 12%:falsefalse", "GPU 85°C:truefalse", "NVMe 95°C:falsetrue"), labels);
    }

    @Test
    void dockerButtonsRunTheirVerbOnTheSelectedContainer() throws Exception {
        for (var verb : List.of("Start", "Stop", "Restart")) {
            selectContainer(DB);
            fire(fx(() -> button(containerTable(), verb)));
            var expected = verb.toLowerCase() + " id-db";
            await(() -> docker.actions.contains(expected));
        }
        assertEquals(List.of("start id-db", "stop id-db", "restart id-db"), docker.actions);
    }

    @Test
    void dockerButtonsDoNothingWithoutSelection() throws Exception {
        fx(() -> {
            containerTable().getSelectionModel().clearSelection();
            return null;
        });
        for (var verb : List.of("Start", "Stop", "Restart", "Logs", "Remove")) {
            fire(fx(() -> button(containerTable(), verb)));
        }
        Thread.sleep(200);
        assertTrue(docker.actions.isEmpty());
        assertTrue(tabs.isEmpty());
        assertNull(fx(ProcessesWindowE2ETest::dialog));
    }

    @Test
    void dockerActionErrorIsShown() throws Exception {
        docker.actionResult = "Error: no such container";
        selectContainer(WEB);
        fire(fx(() -> button(containerTable(), "Stop")));
        await(() -> "Error: no such container".equals(statusText()));
    }

    @Test
    void removeAsksFirstAndOnlyThenRemoves() throws Exception {
        selectContainer(DB);
        fire(fx(() -> button(containerTable(), "Remove")));
        await(() -> dialog() != null);
        assertEquals("Remove container postgres-db?", fx(() -> dialog().getHeaderText()));
        answer("Cancel");
        Thread.sleep(150);
        assertTrue(docker.actions.isEmpty());
        fire(fx(() -> button(containerTable(), "Remove")));
        answer("Remove");
        await(() -> docker.actions.contains("rm id-db"));
    }

    @Test
    void logsOpensADockerLogsTabInTheHomeFolder() throws Exception {
        selectContainer(WEB);
        fire(fx(() -> button(containerTable(), "Logs")));
        assertEquals(List.of("docker logs -f --tail 200 web @ " + HOME), tabs);
    }

    @Test
    void containerContextMenuHasTheSameActionsAndTheyWork() throws Exception {
        var items = fx(() -> containerTable().getContextMenu().getItems().stream().map(i -> i.getText()).toList());
        assertEquals(List.of("Start", "Stop", "Restart", "Logs", "Remove"), items);
        selectContainer(WEB);
        fx(() -> {
            containerTable().getContextMenu().getItems().get(1).fire();
            return null;
        });
        await(() -> docker.actions.contains("stop id-web"));
        fx(() -> {
            containerTable().getContextMenu().getItems().get(3).fire();
            return null;
        });
        assertEquals(1, tabs.size());
    }

    @Test
    void stopAndKillConfirmThenSignalTheSelectedProcess() throws Exception {
        await(() -> processTable().getItems().size() == 2);
        selectProcess(APP);
        fire(fx(() -> button(processTable(), "Stop")));
        await(() -> dialog() != null);
        assertEquals("Stop process 4242?", fx(() -> dialog().getHeaderText()));
        answer("Stop");
        await(() -> signals.size() == 1);
        fire(fx(() -> button(processTable(), "Kill")));
        assertEquals("Kill process 4242?", awaitHeader());
        answer("Kill");
        await(() -> signals.size() == 2);
        assertEquals(List.of("stop 4242", "kill 4242"), signals);
    }

    private static String awaitHeader() throws Exception {
        await(() -> dialog() != null);
        return fx(() -> dialog().getHeaderText());
    }

    @Test
    void cancellingStopOrKillSignalsNothing() throws Exception {
        selectProcess(VITE);
        fire(fx(() -> button(processTable(), "Kill")));
        answer("Cancel");
        fire(fx(() -> button(processTable(), "Stop")));
        answer("Cancel");
        assertTrue(signals.isEmpty());
    }

    @Test
    void stopWithoutSelectionDoesNothing() throws Exception {
        fx(() -> {
            processTable().getSelectionModel().clearSelection();
            return null;
        });
        fire(fx(() -> button(processTable(), "Stop")));
        fire(fx(() -> button(processTable(), "Kill")));
        fire(fx(() -> button(processTable(), "Open folder in shell")));
        assertNull(fx(ProcessesWindowE2ETest::dialog));
        assertTrue(signals.isEmpty());
        assertTrue(tabs.isEmpty());
    }

    @Test
    void openFolderRunsAPlainShellInTheProcessFolder() throws Exception {
        selectProcess(VITE);
        fire(fx(() -> button(processTable(), "Open folder in shell")));
        assertEquals(List.of("null @ /srv/site"), tabs);
    }

    @Test
    void processContextMenuHasStopKillOpenFolder() throws Exception {
        var items = fx(() -> processTable().getContextMenu().getItems().stream().map(i -> i.getText()).toList());
        assertEquals(List.of("Stop", "Kill", "Open folder in shell"), items);
        selectProcess(APP);
        fx(() -> {
            processTable().getContextMenu().getItems().get(2).fire();
            return null;
        });
        assertEquals(List.of("null @ " + HOME + "/work/app"), tabs);
    }

    @Test
    void selectionSurvivesAReload() throws Exception {
        selectContainer(DB);
        selectProcess(VITE);
        fire(toolbarButtonOnFx("Refresh"));
        var before = docker.lists;
        await(() -> docker.lists > before);
        fx(() -> null);
        assertEquals("id-db", fx(() -> containerTable().getSelectionModel().getSelectedItem().id()));
        assertEquals(4343L, fx(() -> processTable().getSelectionModel().getSelectedItem().pid()));
    }

    @Test
    void openingAgainReusesTheWindow() throws Exception {
        var first = fx(ProcessesWindow::openStage);
        assertNotNull(first);
        fx(() -> {
            ProcessesWindow.open(null, null);
            return null;
        });
        assertSame(first, fx(ProcessesWindow::openStage));
        fx(() -> {
            first.hide();
            return null;
        });
        assertNull(fx(ProcessesWindow::openStage));
        assertFalse(first.isShowing());
    }

    private static Label hiddenLabel() {
        return (Label) ((HBox) hideIde().getParent()).getChildren().stream().filter(n -> n instanceof Label)
                .findFirst().orElseThrow();
    }

    @Test
    void actionButtonsAreDisabledWithoutASelectionAndEnabledWithOne() throws Exception {
        fx(() -> {
            containerTable().getSelectionModel().clearSelection();
            processTable().getSelectionModel().clearSelection();
            return null;
        });
        var dockerVerbs = List.of("Start", "Stop", "Restart", "Logs", "Remove");
        var processVerbs = List.of("Stop", "Kill", "Open folder in shell");
        for (var verb : dockerVerbs) {
            assertTrue(fx(() -> button(containerTable(), verb).isDisable()), "docker " + verb);
        }
        for (var verb : processVerbs) {
            assertTrue(fx(() -> button(processTable(), verb).isDisable()), "process " + verb);
        }
        selectContainer(WEB);
        for (var verb : dockerVerbs) {
            assertFalse(fx(() -> button(containerTable(), verb).isDisable()), "docker " + verb);
        }
        for (var verb : processVerbs) {
            assertTrue(fx(() -> button(processTable(), verb).isDisable()), "process " + verb + " (no process selected)");
        }
        await(() -> processTable().getItems().size() == 2);
        selectProcess(APP);
        for (var verb : processVerbs) {
            assertFalse(fx(() -> button(processTable(), verb).isDisable()), "process " + verb);
        }
    }

    @Test
    void hiddenCountSaysHowManyProcessesTheIdeFilterHides() throws Exception {
        await(() -> processTable().getItems().size() == 2);
        assertEquals("1 hidden", fx(() -> hiddenLabel().getText()));
        assertTrue(fx(() -> hiddenLabel().isVisible()));
        fx(() -> {
            filter().setText("idea"); // only the IDE process matches, and it is hidden
            return null;
        });
        assertEquals(0, fx(() -> processTable().getItems().size()));
        assertEquals("1 hidden", fx(() -> hiddenLabel().getText()));
        fx(() -> {
            filter().setText("vite"); // the IDE process no longer matches: nothing is hidden by the checkbox
            return null;
        });
        assertEquals("", fx(() -> hiddenLabel().getText()));
        assertFalse(fx(() -> hiddenLabel().isVisible()));
        fx(() -> {
            filter().setText("");
            hideIde().fire(); // show everything
            return null;
        });
        assertEquals("", fx(() -> hiddenLabel().getText()));
        assertEquals(3, fx(() -> processTable().getItems().size()));
    }

    @Test
    void containerActionShowsWhatItIsDoingWhileItRuns() throws Exception {
        docker.actionGate = new CountDownLatch(1);
        selectContainer(WEB);
        fire(fx(() -> button(containerTable(), "Stop")));
        await(() -> "Stopping web\u2026".equals(statusText()));
        docker.actionGate.countDown();
        await(() -> "2 containers, 2 processes".equals(statusText()));
        docker.actionGate = new CountDownLatch(1);
        selectContainer(DB);
        fire(fx(() -> button(containerTable(), "Restart")));
        await(() -> "Restarting postgres-db\u2026".equals(statusText()));
        docker.actionGate.countDown();
        await(() -> "2 containers, 2 processes".equals(statusText()));
    }

    @Test
    void footerHintListsTheKeysAndEscCloses() throws Exception {
        var hint = fx(() -> root().lookupAll(".key-hint").stream().map(n -> ((Label) n).getText()).toList());
        assertEquals(1, hint.size());
        assertTrue(hint.get(0).contains("Esc close"), hint.get(0));
        assertTrue(hint.get(0).contains("F5 refresh"), hint.get(0));
        var primary = fx(() -> root().lookupAll(".button.primary").stream().map(n -> ((Button) n).getText()).toList());
        assertTrue(primary.isEmpty(), primary.toString());
    }
}
