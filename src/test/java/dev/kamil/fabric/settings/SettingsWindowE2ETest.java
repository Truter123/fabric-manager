package dev.kamil.fabric.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.FxHarness;
import dev.kamil.fabric.session.Account;
import dev.kamil.fabric.session.Database;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the real Settings window controls. user.home points at a temporary folder for the test, so accounts, the
 * video cache and the Claude config browser never touch the real home; skipped without a display.
 */
class SettingsWindowE2ETest {

    @TempDir
    Path dir;

    private String realHome;
    private Database db;
    private AppSettings settings;
    private Account account;

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
    void openWindow() throws Exception {
        realHome = System.getProperty("user.home");
        var home = Files.createDirectories(dir.resolve("home"));
        Files.createDirectories(home.resolve(".claude"));
        Files.createDirectories(home.resolve(".claude-work"));
        System.setProperty("user.home", home.toString());
        db = Database.at(dir.resolve("e2e")).migrate();
        settings = new AppSettings(db);
        account = Account.personal(home);
        var project = Files.createDirectories(dir.resolve("project"));
        fx(() -> {
            SettingsWindow.open(null, settings, project, account);
            return null;
        });
    }

    @AfterEach
    void close() throws Exception {
        try {
            fx(() -> {
                for (var w : List.copyOf(Window.getWindows())) {
                    w.hide();
                }
                return null;
            });
        } finally {
            System.setProperty("user.home", realHome);
        }
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

    private static Stage stage() {
        return SettingsWindow.openStage();
    }

    @SuppressWarnings("unchecked")
    private static ListView<String> groups() {
        return (ListView<String>) stage().getScene().lookup(".settings-groups");
    }

    private static TextField search() {
        return (TextField) stage().getScene().lookup(".settings-search");
    }

    private void group(String name) throws Exception {
        fx(() -> {
            groups().getSelectionModel().select(name);
            return null;
        });
    }

    /** The visible row (managed) whose label or checkbox text is {@code label}. */
    private static Node row(String label) {
        for (var node : stage().getScene().getRoot().lookupAll(".settings-row")) {
            if (!node.isManaged()) {
                continue;
            }
            for (var child : ((javafx.scene.layout.Pane) node).getChildren()) {
                if (child instanceof Label l && label.equals(l.getText()) && l.getStyleClass().contains("settings-label")
                        || child instanceof CheckBox c && label.equals(c.getText())) {
                    return child instanceof Label ? node : child;
                }
            }
        }
        throw new AssertionError("no visible row " + label);
    }

    private static CheckBox box(String label) {
        return (CheckBox) row(label);
    }

    private static <T extends Node> T control(String label, Class<T> type) {
        for (var child : ((javafx.scene.layout.Pane) row(label)).getChildren()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof javafx.scene.layout.HBox inline) { // a number field sits beside its error text
                for (var inner : inline.getChildren()) {
                    if (type.isInstance(inner)) {
                        return type.cast(inner);
                    }
                }
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + label);
    }

    private static boolean saved() {
        return stage().getScene().lookup(".settings-saved").isVisible();
    }

    private static void clickCheck(CheckBox box) {
        box.fire();
    }

    private static DialogPane dialog() {
        for (var w : Window.getWindows()) {
            if (w.isShowing() && w.getScene() != null && w.getScene().getRoot() instanceof DialogPane p) {
                return p;
            }
        }
        return null;
    }

    /** Presses the button of the modal confirm dialog the last runLater opened. */
    private static void answer(String text) throws Exception {
        await(() -> dialog() != null);
        fx(() -> {
            var pane = dialog();
            for (var type : pane.getButtonTypes()) {
                if (type.getText().equals(text)) {
                    ((Button) pane.lookupButton(type)).fire();
                }
            }
            return null;
        });
    }

    private static void press(KeyCode code) throws Exception {
        fx(() -> {
            var target = stage().getScene().getFocusOwner() != null ? stage().getScene().getFocusOwner()
                    : stage().getScene().getRoot();
            target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
            return null;
        });
    }

    // ---- tests

    @Test
    void listsAllGroupsAndRendersEveryCellAfterRepeatedUpdates() throws Exception {
        var expected = List.of("General", "Terminal", "Notifications", "Accounts", "Body double", "Quotes", "Claude Code");
        assertEquals(expected, fx(() -> List.copyOf(groups().getItems())));
        for (var g : expected) {
            group(g);
            assertEquals(g, fx(() -> ((Label) stage().getScene().lookup(".settings-title")).getText()));
        }
        fx(() -> {
            groups().refresh();
            return null;
        });
        var texts = fx(() -> groups().lookupAll(".list-cell").stream().map(n -> ((ListCell<?>) n).getText())
                .filter(t -> t != null).toList());
        assertTrue(texts.contains("General"));
        assertTrue(texts.contains("Claude Code  ›"), texts.toString());
    }

    @Test
    void generalCheckboxSavesAndFlashesSaved() throws Exception {
        assertFalse(fx(SettingsWindowE2ETest::saved));
        fx(() -> {
            var b = box("Show Agents panel on start");
            assertTrue(b.isSelected());
            clickCheck(b);
            return null;
        });
        assertFalse(settings.getBoolean(SettingsWindow.SHOW_AGENTS, true));
        assertTrue(fx(SettingsWindowE2ETest::saved));
        await(() -> !saved());
        // persisted to the database
        assertFalse(new AppSettings(db).getBoolean(SettingsWindow.SHOW_AGENTS, true));
    }

    @Test
    void terminalFieldsValidateAndSave() throws Exception {
        group("Terminal");
        fx(() -> {
            var size = control("Size", TextField.class);
            assertEquals("14", size.getText());
            size.setText("20");
            return null;
        });
        assertEquals(20, settings.getInt(dev.kamil.fabric.terminal.TerminalSettings.FONT_SIZE_KEY, -1));
        fx(() -> {
            var size = control("Size", TextField.class);
            size.setText("5");
            assertTrue(size.getPseudoClassStates().stream().anyMatch(p -> p.getPseudoClassName().equals("invalid")));
            size.setText("5x");
            assertEquals("5", size.getText(), "non-digits are rejected");
            return null;
        });
        assertEquals(20, settings.getInt(dev.kamil.fabric.terminal.TerminalSettings.FONT_SIZE_KEY, -1),
                "an out-of-range value is not saved");
        fx(() -> {
            var scroll = control("Scrollback", TextField.class);
            scroll.setText("7000");
            clickCheck(box("Copy on select"));
            clickCheck(box("Audible bell"));
            return null;
        });
        assertEquals(7000, settings.getInt(dev.kamil.fabric.terminal.TerminalSettings.SCROLLBACK, -1));
        assertTrue(settings.getBoolean(dev.kamil.fabric.terminal.TerminalSettings.COPY_ON_SELECT, false));
        assertTrue(settings.getBoolean(dev.kamil.fabric.terminal.TerminalSettings.AUDIBLE_BELL, false));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fontChoiceSavesAndAutoRemoves() throws Exception {
        group("Terminal");
        var key = dev.kamil.fabric.terminal.TerminalSettings.FONT_FAMILY;
        fx(() -> {
            var combo = (ComboBox<String>) control("Font", ComboBox.class);
            assertEquals("Auto", combo.getValue());
            if (combo.getItems().size() > 1) {
                combo.setValue(combo.getItems().get(1));
            }
            return null;
        });
        var combo = fx(() -> ((ComboBox<String>) control("Font", ComboBox.class)));
        if (fx(() -> combo.getItems().size()) > 1) {
            assertEquals(fx(() -> combo.getItems().get(1)), settings.getString(key, ""));
            fx(() -> {
                combo.setValue("Auto");
                return null;
            });
            assertEquals("", settings.getString(key, ""));
        }
    }

    @Test
    void notificationTogglesSave() throws Exception {
        group("Notifications");
        fx(() -> {
            clickCheck(box("Sound"));
            clickCheck(box("Notify when Claude finishes"));
            return null;
        });
        assertFalse(settings.getBoolean(dev.kamil.fabric.claude.Notifier.SOUND, true));
        assertFalse(settings.getBoolean(dev.kamil.fabric.claude.Notifier.FINISHED, true));
        assertTrue(settings.getBoolean(dev.kamil.fabric.claude.Notifier.DESKTOP, true));
    }

    @Test
    void accountLabelsSaveAndClear() throws Exception {
        group("Accounts");
        fx(() -> {
            assertNotNull(row("Label for personal"));
            assertNotNull(row("Label for work"));
            control("Label for work", TextField.class).setText("  Job ");
            return null;
        });
        assertEquals("Job", settings.getString(Account.labelKey("work"), null));
        fx(() -> {
            control("Label for work", TextField.class).setText("");
            return null;
        });
        assertNull(settings.getString(Account.labelKey("work"), null));
        fx(() -> {
            var work = control("Work folder", javafx.scene.layout.HBox.class);
            assertEquals(Account.WORK_FOLDER, ((TextField) work.getChildren().get(0)).getText());
            assertFalse(((TextField) work.getChildren().get(0)).isEditable());
            return null;
        });
    }

    @Test
    void bodyDoubleAndQuotesNumbersSave() throws Exception {
        group("Body double");
        fx(() -> {
            control("Cache size (MB)", TextField.class).setText("500");
            return null;
        });
        assertEquals(500, settings.getInt(dev.kamil.fabric.bodydouble.VideoDownloader.CACHE_MB_KEY, -1));
        // the cache folder lives under the temporary home
        fx(() -> {
            var folder = control("Cache folder", javafx.scene.layout.HBox.class);
            assertTrue(((TextField) folder.getChildren().get(0)).getText().startsWith(dir.toString()));
            return null;
        });
        group("Quotes");
        fx(() -> {
            control("Change quote every (minutes)", TextField.class).setText("30");
            return null;
        });
        assertEquals(30, settings.getInt(dev.kamil.fabric.quotes.QuoteBar.INTERVAL_KEY, -1));
    }

    @Test
    void searchSwitchesGroupDimsOthersAndEscapeClears() throws Exception {
        fx(() -> {
            search().setText("scrollback");
            return null;
        });
        assertEquals("Terminal", fx(() -> groups().getSelectionModel().getSelectedItem()));
        await(() -> groups().lookupAll(".list-cell").stream().anyMatch(n -> n.getStyleClass().contains("settings-dim")));
        var dimmed = fx(() -> groups().lookupAll(".list-cell").stream()
                .filter(n -> n.getStyleClass().contains("settings-dim"))
                .map(n -> ((ListCell<?>) n).getText()).toList());
        assertTrue(dimmed.contains("General"), dimmed.toString());
        assertFalse(dimmed.contains("Terminal"), dimmed.toString());
        fx(() -> {
            assertNotNull(row("Scrollback"));
            return null;
        });
        // a non-matching row of the selected group is hidden
        assertTrue(fx(() -> stage().getScene().getRoot().lookupAll(".settings-row").stream()
                .anyMatch(n -> !n.isManaged())));
        fx(() -> {
            search().requestFocus();
            return null;
        });
        press(KeyCode.ESCAPE);
        assertEquals("", fx(() -> search().getText()));
        assertTrue(stage().isShowing());
        var window = fx(SettingsWindowE2ETest::stage);
        press(KeyCode.ESCAPE);
        await(() -> !window.isShowing());
    }

    @Test
    void resetGroupAsksAndRestoresDefaults() throws Exception {
        group("Terminal");
        var key = dev.kamil.fabric.terminal.TerminalSettings.FONT_SIZE_KEY;
        fx(() -> {
            control("Size", TextField.class).setText("30");
            return null;
        });
        assertEquals(30, settings.getInt(key, -1));
        Platform.runLater(() -> ((Button) stage().getScene().getRoot().lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && "Reset group".equals(b.getText())).findFirst().orElseThrow()).fire());
        answer("Cancel");
        await(() -> dialog() == null);
        assertEquals(30, settings.getInt(key, -1), "cancel keeps the value");
        Platform.runLater(() -> ((Button) stage().getScene().getRoot().lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && "Reset group".equals(b.getText())).findFirst().orElseThrow()).fire());
        answer("Reset");
        await(() -> dialog() == null);
        assertEquals(-1, settings.getInt(key, -1));
        assertEquals("14", fx(() -> control("Size", TextField.class).getText()));
    }

    @Test
    void openingAgainReusesTheWindow() throws Exception {
        var first = fx(SettingsWindowE2ETest::stage);
        fx(() -> {
            SettingsWindow.open(null, settings, dir, account);
            return null;
        });
        assertSame(first, fx(SettingsWindowE2ETest::stage));
    }

    @Test
    void aNumberOutsideItsRangeShowsTheRangeBesideTheField() throws Exception {
        group("Terminal");
        fx(() -> {
            var size = control("Size", TextField.class);
            var error = (Label) size.getParent().lookup(".inline-error");
            assertFalse(error.isVisible() || error.isManaged(), "no error while the value is valid");
            size.setText("5");
            assertTrue(error.isVisible() && error.isManaged());
            assertEquals("6 to 72", error.getText());
            size.setText("");
            assertTrue(error.isVisible(), "an empty field is not a number either");
            size.setText("20");
            assertFalse(error.isVisible() || error.isManaged(), "the error goes once the value is valid");
            var scroll = control("Scrollback", TextField.class);
            scroll.setText("5");
            assertEquals("100 to 1000000", ((Label) scroll.getParent().lookup(".inline-error")).getText());
            return null;
        });
    }

    @Test
    void aSearchWithNoMatchSaysSo() throws Exception {
        fx(() -> {
            search().setText("zzzzqq");
            return null;
        });
        assertTrue(fx(() -> stage().getScene().getRoot().lookupAll(".settings-help").stream()
                .anyMatch(n -> n instanceof Label l && "No settings match".equals(l.getText()))));
        fx(() -> {
            search().setText("scrollback");
            return null;
        });
        assertFalse(fx(() -> stage().getScene().getRoot().lookupAll(".settings-help").stream()
                .anyMatch(n -> n instanceof Label l && "No settings match".equals(l.getText()))));
    }

    @Test
    void openingAgainRefreshesTheAccountForTheClaudeBrowser() throws Exception {
        var work = Account.available(java.nio.file.Path.of(System.getProperty("user.home"))).stream()
                .filter(a -> a.name().equals("work")).findFirst().orElseThrow();
        fx(() -> {
            SettingsWindow.open(null, settings, dir, work);
            return null;
        });
        group("Claude Code");
        fx(() -> {
            @SuppressWarnings("unchecked")
            var accounts = (ComboBox<Account>) ((javafx.scene.layout.Pane) control("Claude config browser",
                    javafx.scene.layout.VBox.class)).getChildren().stream().filter(n -> n instanceof ComboBox)
                    .findFirst().orElseThrow();
            assertEquals("work", accounts.getValue().name());
            return null;
        });
    }

    @Test
    void claudeGroupOffersAccountsAndOpenButton() throws Exception {
        group("Claude Code");
        fx(() -> {
            @SuppressWarnings("unchecked")
            var accounts = (ComboBox<Account>) ((javafx.scene.layout.Pane) control("Claude config browser",
                    javafx.scene.layout.VBox.class)).getChildren().stream().filter(n -> n instanceof ComboBox)
                    .findFirst().orElseThrow();
            assertEquals(List.of("personal", "work"), accounts.getItems().stream().map(Account::name).toList());
            assertEquals("personal", accounts.getValue().name());
            return null;
        });
    }

    @Test
    void showsOneKeyHintListingEsc() throws Exception {
        var hints = fx(() -> stage().getScene().getRoot().lookupAll(".key-hint").stream().map(n -> ((Label) n).getText()).toList());
        assertEquals(1, hints.size(), hints.toString());
        assertTrue(hints.get(0).contains("Esc"), hints.get(0));
        assertTrue(hints.get(0).contains("clear search"), hints.get(0));
    }
}
