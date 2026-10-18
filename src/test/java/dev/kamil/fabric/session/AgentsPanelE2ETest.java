package dev.kamil.fabric.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.FxHarness;
import dev.kamil.fabric.terminal.TerminalTab;
import dev.kamil.fabric.terminal.TerminalTab.Activity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the real agents panel over real tabs. The "claude" tool runs {@code true}, so no Claude is started, and
 * user.home is a temporary folder; skipped without a display.
 */
class AgentsPanelE2ETest {

    private static final Tool FAKE_CLAUDE = new Tool("claude", "true");

    @TempDir
    Path dir;

    private String realHome;
    private Stage stage;
    private TabPane tabs;
    private AgentsPanel panel;
    private String contextText = "";

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
    }

    @BeforeEach
    void open() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(dir.resolve("home")).toString());
        fx(() -> {
            tabs = new TabPane();
            panel = new AgentsPanel(tabs, id -> contextText);
            stage = new Stage();
            stage.setScene(new javafx.scene.Scene(new javafx.scene.layout.BorderPane(tabs, null, panel, null, null),
                    900, 500));
            stage.show();
            return null;
        });
    }

    @AfterEach
    void close() throws Exception {
        fx(() -> {
            tabs.getTabs().clear();
            stage.close();
            return null;
        });
        System.setProperty("user.home", realHome);
    }

    @Test
    void emptyPanelSaysNoAgents() throws Exception {
        assertEquals("No agents", header());
        assertEquals(0, rows().size());
    }

    @Test
    void listsOnlyClaudeTabsAndSortsWaitingFirst() throws Exception {
        var alpha = addTab(FAKE_CLAUDE, "alpha");
        var beta = addTab(FAKE_CLAUDE, "beta");
        addTab(Tool.SHELL, "plain");
        assertEquals(List.of("alpha", "beta"), names());
        assertEquals("2 agents", header());

        fx(() -> {
            beta.setActivity(Activity.WAITING);
            alpha.setActivity(Activity.WORKING);
            panel.refresh();
            return null;
        });
        assertEquals(List.of("beta", "alpha"), names());
        assertEquals("1 working · 1 needs input", header());
    }

    @Test
    void clickingARowSelectsItsTab() throws Exception {
        var alpha = addTab(FAKE_CLAUDE, "alpha");
        var beta = addTab(FAKE_CLAUDE, "beta");
        fx(() -> {
            tabs.getSelectionModel().select(alpha);
            return null;
        });
        assertSame(alpha, fx(() -> tabs.getSelectionModel().getSelectedItem()));
        fx(() -> {
            var row = rows().stream().filter(r -> text(r).startsWith("beta")).findFirst().orElseThrow();
            row.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 1, 1, 1, 1, MouseButton.PRIMARY, 1, false, false,
                    false, false, true, false, false, true, false, false, null));
            return null;
        });
        assertSame(beta, fx(() -> tabs.getSelectionModel().getSelectedItem()));
        assertTrue(fx(() -> rows().stream().filter(r -> text(r).startsWith("beta")).findFirst().orElseThrow()
                .getStyleClass().contains("agent-row-selected")));
    }

    @Test
    void closingATabRemovesItsRowAndShowsContextAndUnseen() throws Exception {
        var alpha = addTab(FAKE_CLAUDE, "alpha");
        var beta = addTab(FAKE_CLAUDE, "beta");
        contextText = "ctx 68k / 200k · 34%";
        fx(() -> {
            alpha.setUnseen(true);
            panel.refresh();
            return null;
        });
        assertTrue(fx(() -> rows().stream().anyMatch(r -> text(r).contains("ctx 68k / 200k · 34%"))));
        assertTrue(fx(() -> panel.lookupAll(".agent-unseen").size() == 1));
        fx(() -> {
            tabs.getTabs().remove(beta);
            return null;
        });
        assertEquals(List.of("alpha"), names());
        assertEquals("1 agent", header());
        assertFalse(fx(() -> rows().isEmpty()));
    }

    // ---- harness

    private TerminalTab addTab(Tool tool, String folder) throws Exception {
        var path = Files.createDirectories(dir.resolve(folder));
        return fx(() -> {
            var tab = TerminalTab.open(tool, path, Account.personal(dir.resolve("home")),
                    TerminalTab.Lifecycle.NONE);
            tabs.getTabs().add(tab);
            return tab;
        });
    }

    private String header() throws Exception {
        return fx(() -> ((Label) panel.lookup(".agents-header")).getText());
    }

    private List<String> names() throws Exception {
        return fx(() -> rows().stream().map(AgentsPanelE2ETest::text).map(t -> t.split("\n")[0]).toList());
    }

    private List<Node> rows() {
        return panel.lookupAll(".agent-row").stream().toList();
    }

    private static String text(Node row) {
        return ((javafx.scene.layout.VBox) row).lookupAll(".label").stream()
                .map(n -> ((Label) n).getText()).reduce((a, b) -> a + "\n" + b).orElse("");
    }

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
}
