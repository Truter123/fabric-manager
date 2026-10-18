package dev.kamil.fabric.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.EscapeClosesWindowsE2ETest;
import dev.kamil.fabric.FxHarness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.Arguments;

/** Drives the real Water window controls against a temporary water-reminder directory. */
class WaterWindowE2ETest {

    @TempDir
    Path dir;

    private WaterStore store;

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
    void setUp() {
        store = new WaterStore(dir);
    }

    @AfterEach
    void close() throws Exception {
        fx(() -> {
            var stage = WaterWindow.openStage();
            if (stage != null) {
                stage.hide();
            }
            return null;
        });
    }

    /** The case of {@link EscapeClosesWindowsE2ETest}. */
    static Stream<Arguments> escapeCase() {
        return Stream.of(EscapeClosesWindowsE2ETest.escapeCase("Water", dir -> {
            var stage = FxHarness.fx(() -> {
                WaterWindow.open(null, dir);
                return WaterWindow.openStage();
            });
            // the window fills in once its store has been read
            FxHarness.await(() -> label("Today:") != null);
            return stage;
        }, () -> FxHarness.fx(() -> {
            var stage = WaterWindow.openStage();
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

    private void open() throws Exception {
        fx(() -> {
            WaterWindow.open(null, dir);
            return null;
        });
        await(() -> label("Today:") != null);
    }

    private static Node root() {
        return WaterWindow.openStage().getScene().getRoot();
    }

    /** Call on the FX thread. */
    private static Label label(String prefix) {
        for (var n : root().lookupAll(".label")) {
            if (n instanceof Label l && l.getText() != null && l.getText().startsWith(prefix)) {
                return l;
            }
        }
        return null;
    }

    private static String text(String prefix) {
        var l = label(prefix);
        return l == null ? "" : l.getText();
    }

    /** Call on the FX thread. */
    private static Button button(String prefix) {
        for (var n : root().lookupAll(".button")) {
            if (n instanceof Button b && b.getText() != null && b.getText().startsWith(prefix)) {
                return b;
            }
        }
        throw new AssertionError("no button " + prefix);
    }

    @SuppressWarnings("unchecked")
    private static Spinner<Integer> spinner(int index) {
        return (Spinner<Integer>) root().lookupAll(".spinner").toArray()[index];
    }

    private void click(String prefix) throws Exception {
        fx(() -> {
            button(prefix).fire();
            return null;
        });
    }

    private static ToggleButton toggle() {
        return (ToggleButton) root().lookup(".toggle-button");
    }

    private long logLines() throws Exception {
        return store.readLog().size();
    }

    // ---- tests

    @Test
    void showsTodaysTotalsProgressAndLogNewestFirst() throws Exception {
        var today = LocalDate.now();
        store.append(today, LocalTime.of(8, 0), 250, WaterStore.DRANK);
        store.append(today, LocalTime.of(9, 0), 500, WaterStore.DRANK);
        store.append(today, LocalTime.of(10, 0), 250, WaterStore.SKIPPED);
        store.append(today.minusDays(1), LocalTime.of(10, 0), 999, WaterStore.DRANK);
        store.writeConfig(new WaterStore.Config(250, 60, 2000, true, true, true));
        open();
        await(() -> text("Today:").equals("Today: 750 / 2000 ml"));
        assertEquals("2 drank, 1 skipped, 0 missed", fx(() -> text("2 drank")));
        assertEquals(0.375, fx(() -> ((ProgressBar) root().lookup(".progress-bar")).getProgress()), 1e-9);
        assertEquals("Drank 250 ml", fx(() -> button("Drank").getText()));
        assertEquals("Reminder: on", fx(() -> toggle().getText()));
        assertEquals(List.of("10:00   250 ml   skipped", "09:00   500 ml   drank", "08:00   250 ml   drank"),
                fx(() -> root().lookupAll(".label").stream().map(n -> ((Label) n).getText())
                        .filter(t -> t != null && t.matches("\\d\\d:\\d\\d .*")).toList()));
    }

    @Test
    void drankButtonLogsAGlassAndUpdatesTheView() throws Exception {
        open();
        click("Drank");
        await(() -> text("Today:").equals("Today: 250 / 2000 ml"));
        assertEquals(1, logLines());
        assertEquals(WaterStore.DRANK, store.readLog().get(0).status());
        click("Drank");
        await(() -> text("Today:").equals("Today: 500 / 2000 ml"));
        assertEquals("2 drank, 0 skipped, 0 missed", fx(() -> text("2 drank")));
    }

    @Test
    void skipButtonLogsASkipWithoutCountingWater() throws Exception {
        open();
        click("Skip");
        await(() -> text("0 drank, 1 skipped").startsWith("0 drank, 1 skipped"));
        assertEquals("Today: 0 / 2000 ml", fx(() -> text("Today:")));
        assertEquals(WaterStore.SKIPPED, store.readLog().get(0).status());
    }

    @Test
    void saveWritesSettingsAndDrankLabelFollows() throws Exception {
        open();
        fx(() -> {
            spinner(0).getValueFactory().setValue(300);
            spinner(1).getValueFactory().setValue(45);
            spinner(2).getValueFactory().setValue(3000);
            return null;
        });
        click("Save");
        await(() -> button("Drank").getText().equals("Drank 300 ml"));
        var config = store.readConfig();
        assertEquals(300, config.ml());
        assertEquals(45, config.everyMin());
        assertEquals(3000, config.goalMl());
        assertEquals("Today: 0 / 3000 ml", fx(() -> text("Today:")));
        click("Drank");
        await(() -> text("Today:").equals("Today: 300 / 3000 ml"));
    }

    @Test
    void typedSpinnerTextIsCommittedBeforeSave() throws Exception {
        open();
        fx(() -> {
            spinner(0).getEditor().setText("400");
            spinner(0).increment(0); // what losing focus does
            return null;
        });
        click("Save");
        await(() -> button("Drank").getText().equals("Drank 400 ml"));
        assertEquals(400, store.readConfig().ml());
    }

    @Test
    void reminderToggleWritesEnabledFlag() throws Exception {
        open();
        assertFalse(fx(() -> toggle().isSelected()));
        assertEquals("Reminder: off", fx(() -> toggle().getText()));
        fx(() -> {
            toggle().fire();
            return null;
        });
        await(() -> toggle().getText().equals("Reminder: on") && toggle().isSelected());
        assertTrue(store.readConfig().enabled());
        assertTrue(Files.readString(dir.resolve("config")).contains("ENABLED=1"));
        fx(() -> {
            toggle().fire();
            return null;
        });
        await(() -> toggle().getText().equals("Reminder: off") && !toggle().isSelected());
        assertFalse(store.readConfig().enabled());
        assertTrue(Files.readString(dir.resolve("config")).contains("ENABLED=0"));
    }

    @Test
    void saveKeepsTheReminderSwitch() throws Exception {
        store.ensure();
        store.writeConfig(new WaterStore.Config(250, 60, 2000, true, true, false));
        open();
        await(() -> toggle().getText().equals("Reminder: off"));
        click("Save");
        Thread.sleep(300);
        await(() -> toggle().getText().equals("Reminder: off"));
        assertFalse(store.readConfig().enabled());
    }

    @Test
    void openAgainReusesTheWindowAndRefreshes() throws Exception {
        open();
        var first = fx(WaterWindow::openStage);
        store.append(LocalDate.now(), LocalTime.of(7, 0), 250, WaterStore.DRANK);
        fx(() -> {
            WaterWindow.open(null, dir);
            return null;
        });
        assertSame(first, fx(WaterWindow::openStage));
        await(() -> text("Today:").equals("Today: 250 / 2000 ml"));
    }

    @Test
    void saveConfirmsWithSavedAndClearsItAfterAMoment() throws Exception {
        open();
        assertEquals("", fx(() -> text("Saved")));
        click("Save");
        await(() -> label("Saved") != null);
        await(() -> label("Saved") == null);
    }

    @Test
    void aSpinnerEditedButNotSavedSurvivesARefresh() throws Exception {
        open();
        fx(() -> {
            spinner(0).getValueFactory().setValue(400);
            return null;
        });
        store.append(LocalDate.now(), LocalTime.of(7, 0), 250, WaterStore.DRANK);
        fx(() -> {
            WaterWindow.open(null, dir); // reopening an open window refreshes it
            return null;
        });
        await(() -> text("Today:").equals("Today: 250 / 2000 ml"));
        assertEquals(Integer.valueOf(400), fx(() -> spinner(0).getValue()));
        assertEquals(250, store.readConfig().ml());
    }

    @Test
    void footerHintListsTheKeysAndEscCloses() throws Exception {
        open();
        var hint = fx(() -> root().lookupAll(".key-hint").stream().map(n -> ((Label) n).getText()).toList());
        assertEquals(1, hint.size());
        assertTrue(hint.get(0).contains("Esc close"), hint.get(0));
        var primary = fx(() -> root().lookupAll(".button.primary").stream().map(n -> ((Button) n).getText()).toList());
        assertEquals(1, primary.size(), primary.toString());
        assertTrue(primary.get(0).startsWith("Drank"), primary.get(0));
    }
}
