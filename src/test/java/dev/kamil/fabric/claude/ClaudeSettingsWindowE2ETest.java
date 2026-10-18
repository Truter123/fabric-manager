package dev.kamil.fabric.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.FxHarness;
import dev.kamil.fabric.session.Account;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
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
 * Drives the real Claude config browser. user.home and the project are temporary folders, so the real ~/.claude is
 * never read or written; skipped without a display.
 */
class ClaudeSettingsWindowE2ETest {

    @TempDir
    Path dir;

    private String realHome;
    private Path home;
    private Path project;
    private Path userSettings;
    private Path userMemory;
    private Path agent;
    private Path projectSettings;

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
        home = Files.createDirectories(dir.resolve("home"));
        project = Files.createDirectories(dir.resolve("proj"));
        System.setProperty("user.home", home.toString());
        var claude = Files.createDirectories(home.resolve(".claude"));
        userSettings = Files.writeString(claude.resolve("settings.json"), "{\"model\": \"opus\"}\n");
        userMemory = Files.writeString(claude.resolve("CLAUDE.md"), "# user memory\n");
        agent = Files.writeString(Files.createDirectories(claude.resolve("agents")).resolve("helper.md"),
                "---\nname: helper\ndescription: Helps out\n---\nBody\n");
        Files.writeString(home.resolve(".claude.json"), "{\"mcpServers\": {}}\n");
        var projectClaude = Files.createDirectories(project.resolve(".claude"));
        projectSettings = Files.writeString(projectClaude.resolve("settings.json"), "{\"theme\": \"dark\"}\n");
        fx(() -> {
            ClaudeSettingsWindow.open(null, project, Account.personal(home));
            return null;
        });
        await(() -> !files().isEmpty());
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

    private Stage stage() {
        return ClaudeSettingsWindow.openStage(project);
    }

    @SuppressWarnings("unchecked")
    private TreeView<Object> tree() {
        return (TreeView<Object>) (TreeView<?>) stage().getScene().lookup(".tree-view");
    }

    private TextArea editor() {
        return (TextArea) stage().getScene().lookup(".text-area");
    }

    private TextField filter() {
        return (TextField) stage().getScene().lookup(".text-field");
    }

    private Button button(String text) {
        return stage().getScene().getRoot().lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && text.equals(b.getText())).map(n -> (Button) n).findFirst()
                .orElseThrow();
    }

    /** Names of every file row currently in the tree, in order. */
    private List<String> files() {
        var result = new ArrayList<String>();
        collect(tree().getRoot(), result);
        return result;
    }

    private static void collect(TreeItem<Object> item, List<String> out) {
        if (item == null) {
            return;
        }
        if (item.getChildren().isEmpty() && !label(item).isEmpty()) {
            out.add(label(item));
        }
        item.getChildren().forEach(c -> collect(c, out));
    }

    /** The node label: the record's first component, read from its toString. */
    private static String label(TreeItem<Object> item) {
        var text = item.getValue().toString();
        var start = text.indexOf("label=") + 6;
        return text.substring(start, text.indexOf(", description="));
    }

    private void selectFile(String name) throws Exception {
        fx(() -> {
            var found = find(tree().getRoot(), name);
            assertNotNull(found, name + " in " + files());
            tree().getSelectionModel().select(found);
            return null;
        });
    }

    private static TreeItem<Object> find(TreeItem<Object> item, String name) {
        if (item.getChildren().isEmpty() && label(item).equals(name)) {
            return item;
        }
        for (var child : item.getChildren()) {
            var found = find(child, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void open(String name, String expectedText) throws Exception {
        selectFile(name);
        await(() -> editor().getText().equals(expectedText));
    }

    private List<String> labels() {
        return stage().getScene().getRoot().lookupAll(".history-meta").stream()
                .filter(n -> n instanceof Label).map(n -> ((Label) n).getText()).toList();
    }

    private static DialogPane dialog() {
        for (var w : Window.getWindows()) {
            if (w.isShowing() && w.getScene() != null && w.getScene().getRoot() instanceof DialogPane p) {
                return p;
            }
        }
        return null;
    }

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
        await(() -> dialog() == null);
    }

    private static void key(Node target, KeyCode code, boolean control) throws Exception {
        fx(() -> {
            target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, control, false, false));
            return null;
        });
    }

    private void type(String text) throws Exception {
        fx(() -> {
            editor().setText(text);
            return null;
        });
    }

    // ---- tests

    @Test
    void treeShowsScopesKindsAndFiles() throws Exception {
        var all = fx(this::files);
        assertTrue(all.containsAll(List.of("settings.json", "CLAUDE", "helper", ".claude.json")), all.toString());
        var top = fx(() -> tree().getRoot().getChildren().stream().map(i -> label(i)).toList());
        assertTrue(top.contains("User (~/.claude)") && top.contains("Project"), top.toString());
        // every file row renders its cell (cell reuse across updates must not blank rows)
        fx(() -> {
            tree().refresh();
            return null;
        });
        await(() -> tree().lookupAll(".tree-cell").stream().map(n -> (TreeCell<?>) n)
                .anyMatch(c -> c.getGraphic() != null));
        var rendered = fx(() -> tree().lookupAll(".tree-cell").stream().filter(n -> ((TreeCell<?>) n).getGraphic() != null)
                .count());
        assertTrue(rendered >= 5, "rendered cells: " + rendered);
        assertEquals("Claude settings — proj", fx(() -> stage().getTitle()));
    }

    @Test
    void selectingAFileLoadsItAndShowsItsPath() throws Exception {
        assertFalse(fx(() -> editor().isEditable()));
        open("CLAUDE", "# user memory\n");
        assertTrue(fx(() -> editor().isEditable()));
        assertTrue(fx(() -> labels().contains(userMemory.toString())), fx(() -> labels().toString()));
    }

    @Test
    void editingMarksDirtySavesToDiskAndClearsDirty() throws Exception {
        open("CLAUDE", "# user memory\n");
        assertTrue(fx(() -> button("Save").isDisabled() == false));
        type("# changed\n");
        assertTrue(fx(() -> stage().getTitle().startsWith("● ")));
        assertTrue(fx(() -> labels().contains("● modified")));
        fx(() -> {
            button("Save").fire();
            return null;
        });
        await(() -> !stage().getTitle().startsWith("●") && !button("Save").isDisabled());
        assertEquals("# changed\n", Files.readString(userMemory));
        assertFalse(fx(() -> labels().contains("● modified")));
    }

    @Test
    void invalidSettingsJsonShowsNotSavedAndLeavesTheFile() throws Exception {
        open("settings.json", Files.readString(userSettings));
        type("{ broken");
        Platform.runLater(() -> button("Save").fire());
        await(() -> dialog() != null);
        assertEquals("Not saved", fx(() -> dialog().getHeaderText()));
        answer("OK");
        assertEquals("{\"model\": \"opus\"}\n", Files.readString(userSettings));
    }

    @Test
    void ctrlSAcceleratorSavesValidJson() throws Exception {
        open("settings.json", Files.readString(userSettings));
        type("{\"model\": \"sonnet\"}\n");
        key(stage().getScene().getRoot(), KeyCode.S, true);
        await(() -> {
            try {
                return Files.readString(userSettings).contains("sonnet");
            } catch (IOException e) {
                return false;
            }
        });
    }

    @Test
    void filterNarrowsTheTreeAndClearingRestoresIt() throws Exception {
        var before = fx(this::files);
        fx(() -> {
            filter().setText("helper");
            return null;
        });
        assertEquals(List.of("helper"), fx(this::files));
        fx(() -> {
            filter().setText("zzz-nothing");
            return null;
        });
        assertEquals(List.of(), fx(this::files));
        fx(() -> {
            filter().setText("");
            return null;
        });
        assertEquals(before, fx(this::files));
        // description matches too
        fx(() -> {
            filter().setText("helps out");
            return null;
        });
        assertEquals(List.of("helper"), fx(this::files));
        // Ctrl+F focuses the filter
        key(stage().getScene().getRoot(), KeyCode.F, true);
        assertTrue(fx(() -> filter().isFocused()));
    }

    @Test
    void readOnlyFileIsNotEditable() throws Exception {
        open(".claude.json", Files.readString(home.resolve(".claude.json")));
        assertFalse(fx(() -> editor().isEditable()));
        assertTrue(fx(() -> button("Save").isDisabled()));
        assertTrue(fx(() -> labels().stream().anyMatch(t -> t.startsWith("Read-only"))), fx(() -> labels().toString()));
    }

    @Test
    void switchingFilesWithUnsavedChangesAsksAndCancelKeepsTheEdit() throws Exception {
        open("CLAUDE", "# user memory\n");
        type("# draft\n");
        Platform.runLater(() -> tree().getSelectionModel().select(find(tree().getRoot(), "helper")));
        answer("Cancel");
        await(() -> label(tree().getSelectionModel().getSelectedItem()).equals("CLAUDE"));
        assertEquals("# draft\n", fx(() -> editor().getText()));
        Platform.runLater(() -> tree().getSelectionModel().select(find(tree().getRoot(), "helper")));
        answer("Discard");
        await(() -> editor().getText().startsWith("---\nname: helper"));
        assertEquals("# user memory\n", Files.readString(userMemory));
    }

    @Test
    void overwritingAFileChangedOnDiskAsks() throws Exception {
        open("CLAUDE", "# user memory\n");
        type("# mine\n");
        Files.writeString(userMemory, "# theirs\n");
        Files.setLastModifiedTime(userMemory, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        fx(() -> {
            button("Save").fire();
            return null;
        });
        await(() -> dialog() != null);
        assertEquals("File changed on disk", fx(() -> dialog().getHeaderText()));
        answer("Overwrite");
        await(() -> {
            try {
                return Files.readString(userMemory).equals("# mine\n");
            } catch (IOException e) {
                return false;
            }
        });
    }

    @Test
    void reloadPicksUpNewFilesAndKeepsSelection() throws Exception {
        open("CLAUDE", "# user memory\n");
        Files.writeString(Files.createDirectories(home.resolve(".claude/commands")).resolve("ship.md"), "go\n");
        Files.writeString(userMemory, "# edited outside\n");
        fx(() -> {
            button("⟳").fire();
            return null;
        });
        await(() -> files().contains("ship"));
        await(() -> editor().getText().equals("# edited outside\n"));
        assertEquals("CLAUDE", fx(() -> label(tree().getSelectionModel().getSelectedItem())));
        // F5 does the same
        Files.writeString(home.resolve(".claude/commands/other.md"), "x\n");
        key(stage().getScene().getRoot(), KeyCode.F5, false);
        await(() -> files().contains("other"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void newItemCreatesTheStarterFileInTheChosenScopeAndSelectsIt() throws Exception {
        var menu = fx(() -> stage().getScene().getRoot().lookupAll(".menu-button").stream()
                .map(n -> (MenuButton) n).findFirst().orElseThrow());
        assertEquals(4, fx(() -> menu.getItems().size()));
        Platform.runLater(() -> menu.getItems().stream().filter(i -> i.getText().toLowerCase().contains("agent"))
                .findFirst().orElseThrow().fire());
        await(() -> dialog() != null);
        fx(() -> {
            var grid = (javafx.scene.layout.GridPane) dialog().getContent();
            var combos = grid.getChildren().stream().filter(n -> n instanceof ComboBox).toList();
            var scope = (ComboBox<String>) combos.get(1);
            assertTrue(scope.getValue().startsWith("Project"));
            var name = (TextField) grid.getChildren().stream().filter(n -> n instanceof TextField).findFirst().get();
            var create = dialog().lookupButton(dialog().getButtonTypes().stream()
                    .filter(t -> t.getText().equals("Create")).findFirst().get());
            assertTrue(create.isDisabled());
            name.setText("Bad Name");
            assertTrue(create.isDisabled());
            name.setText("new-agent");
            assertFalse(create.isDisabled());
            return null;
        });
        answer("Create");
        var created = project.resolve(".claude/agents/new-agent.md");
        await(() -> Files.exists(created));
        await(() -> files().contains("new-agent"));
        await(() -> tree().getSelectionModel().getSelectedItem() != null
                && label(tree().getSelectionModel().getSelectedItem()).equals("new-agent"));
        await(() -> editor().getText().equals(readQuietly(created)) && !editor().getText().isEmpty());
        assertTrue(fx(() -> editor().getText().contains("new-agent")));
    }

    private static String readQuietly(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            return null;
        }
    }

    @Test
    void escapeClosesAndDirtyCloseAsks() throws Exception {
        open("CLAUDE", "# user memory\n");
        type("# draft\n");
        var window = fx(this::stage);
        key(stage().getScene().getRoot(), KeyCode.ESCAPE, false);
        answer("Cancel");
        assertTrue(window.isShowing());
        key(window.getScene().getRoot(), KeyCode.ESCAPE, false);
        answer("Discard");
        await(() -> !window.isShowing());
        assertNull(fx(this::stage));
        assertEquals("# user memory\n", Files.readString(userMemory));
    }

    @Test
    void openingTheSameProjectAgainReusesTheWindow() throws Exception {
        var first = fx(this::stage);
        fx(() -> {
            ClaudeSettingsWindow.open(null, project, Account.personal(home));
            return null;
        });
        assertSame(first, fx(this::stage));
        assertEquals(1, fx(() -> Window.getWindows().stream().filter(w -> w == first).count()));
        assertEquals("{\"theme\": \"dark\"}\n", Files.readString(projectSettings));
        assertEquals("{\"model\": \"opus\"}\n", Files.readString(userSettings));
        assertEquals("---\nname: helper\ndescription: Helps out\n---\nBody\n", Files.readString(agent));
    }

    @Test
    void showsOneKeyHintListingEsc() throws Exception {
        var hints = fx(() -> stage().getScene().getRoot().lookupAll(".key-hint").stream().map(n -> ((Label) n).getText()).toList());
        assertEquals(1, hints.size(), hints.toString());
        assertTrue(hints.get(0).contains("Esc"), hints.get(0));
        assertTrue(hints.get(0).contains("Ctrl+S"), hints.get(0));
    }

    @Test
    void saveIsThePrimaryButton() throws Exception {
        assertTrue(fx(() -> button("Save").getStyleClass().contains("primary")));
    }
}
