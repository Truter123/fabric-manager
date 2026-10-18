package dev.kamil.fabric.session;

import static dev.kamil.fabric.FxHarness.fx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.FxHarness;
import dev.kamil.fabric.terminal.TerminalTab;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The overview closes itself when another tab is selected, so the keyboard focus (which moves to the selected
 * terminal beneath it) never leaves Esc without an overview to close. Plain tabs stand in for terminal tabs: the
 * grid shows no cards for them, and the selection listener does not care what the tabs are.
 */
class OverviewGridE2ETest {

    @TempDir
    Path dir;

    private final AtomicInteger closed = new AtomicInteger();
    private TabPane tabs;
    private OverviewGrid grid;

    @BeforeAll
    static void startFx() {
        FxHarness.start();
    }

    @BeforeEach
    void build() throws Exception {
        closed.set(0);
        fx(() -> {
            tabs = new TabPane(new Tab("one"), new Tab("two"));
            grid = new OverviewGrid(tabs, id -> "", closed::incrementAndGet);
            return null;
        });
    }

    @Test
    void selectingAnotherTabClosesAnOpenOverview() throws Exception {
        fx(() -> {
            grid.open();
            return null;
        });
        assertTrue(fx(grid::isOpen));

        fx(() -> {
            tabs.getSelectionModel().select(1);
            return null;
        });

        assertFalse(fx(grid::isOpen));
        assertEquals(1, closed.get());
    }

    @Test
    void selectingATabWhileClosedDoesNotCallOnClose() throws Exception {
        fx(() -> {
            tabs.getSelectionModel().select(1);
            tabs.getSelectionModel().select(0);
            return null;
        });

        assertFalse(fx(grid::isOpen));
        assertEquals(0, closed.get());
    }

    @Test
    void headerNamesTheOverviewAndItsKeys() throws Exception {
        fx(() -> {
            grid.open();
            return null;
        });

        var title = fx(() -> ((Label) grid.lookup(".overview-title")).getText());
        var hint = fx(() -> ((Label) grid.lookup(".key-hint")).getText());

        assertEquals("Overview", title);
        assertTrue(hint.contains("Enter open"), hint);
        assertTrue(hint.contains("1–9 jump"), hint);
        assertTrue(hint.contains("Esc close"), hint);
    }

    @Test
    void firstCardsCarryTheDigitThatJumpsToThem() throws Exception {
        var realHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(dir.resolve("home")).toString());
        var cards = new TabPane();
        try {
            fx(() -> {
                for (var name : List.of("alpha", "beta", "gamma")) {
                    var folder = Files.createDirectories(dir.resolve(name));
                    cards.getTabs().add(TerminalTab.open(new Tool("claude", "true"), folder,
                            Account.personal(dir.resolve("home")), TerminalTab.Lifecycle.NONE));
                }
                var overview = new OverviewGrid(cards, id -> "", () -> { });
                overview.refresh();
                // The scroll pane only holds the cards once its skin exists.
                new javafx.scene.Scene(overview, 800, 600);
                overview.applyCss();
                var badges = overview.lookupAll(".overview-badge").stream().map(n -> ((Label) n).getText()).toList();
                assertEquals(List.of("1", "2", "3"), badges);
                return null;
            });
        } finally {
            fx(() -> {
                cards.getTabs().clear();
                return null;
            });
            System.setProperty("user.home", realHome);
        }
    }
}
