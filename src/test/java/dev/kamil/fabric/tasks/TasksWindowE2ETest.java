package dev.kamil.fabric.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.EscapeClosesWindowsE2ETest;
import dev.kamil.fabric.FxHarness;
import dev.kamil.fabric.session.Database;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.Arguments;

/** Drives the real Tasks window controls against a temporary H2 database; skipped without a display. */
class TasksWindowE2ETest {

    @TempDir
    Path dir;

    private Database db;
    private TaskStore store;

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
        db = Database.at(dir.resolve("e2e")).migrate();
        store = new TaskStore(db);
        fx(() -> {
            TasksWindow.open(null, db);
            return null;
        });
        settle();
    }

    @AfterEach
    void close() throws Exception {
        fx(() -> {
            var stage = TasksWindow.openStage();
            if (stage != null) {
                stage.hide();
            }
            return null;
        });
    }

    /** The case of {@link EscapeClosesWindowsE2ETest}. */
    static Stream<Arguments> escapeCase() {
        return Stream.of(EscapeClosesWindowsE2ETest.escapeCase("Tasks", dir -> {
            var db = Database.at(dir.resolve("escape")).migrate();
            var stage = FxHarness.fx(() -> {
                TasksWindow.open(null, db);
                return TasksWindow.openStage();
            });
            // the background load posts to the window when it ends; let it finish first
            FxHarness.await(() -> Thread.getAllStackTraces().keySet().stream()
                    .noneMatch(t -> t.getName().equals("tasks-load")));
            FxHarness.fx(() -> null);
            return stage;
        }, () -> FxHarness.fx(() -> {
            var stage = TasksWindow.openStage();
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

    /** Waits for the background refresh to finish: the load thread is gone and queued UI work has run. */
    private static void settle() throws Exception {
        await(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().equals("tasks-load")));
        fx(() -> null);
        fx(() -> null);
    }

    private static Stage stage() {
        return TasksWindow.openStage();
    }

    private Button button(String text) throws Exception {
        return fx(() -> {
            for (var n : stage().getScene().getRoot().lookupAll(".button")) {
                if (n instanceof Button b && text.equals(b.getText())) {
                    return b;
                }
            }
            throw new AssertionError("no button " + text);
        });
    }

    /** Call on the FX thread. */
    private static TextField input() {
        return (TextField) stage().getScene().getRoot().lookup(".text-field");
    }

    /** Call on the FX thread. */
    private static DatePicker picker() {
        return (DatePicker) stage().getScene().getRoot().lookup(".date-picker");
    }

    private void click(Button b) throws Exception {
        fx(() -> {
            b.fire();
            return null;
        });
        settle();
    }

    private void type(String text) throws Exception {
        fx(() -> {
            input().setText(text);
            return null;
        });
    }

    private void enter() throws Exception {
        fx(() -> {
            input().fireEvent(new ActionEvent());
            return null;
        });
        settle();
    }

    @SuppressWarnings("unchecked")
    private ListView<Object> list() {
        return (ListView<Object>) TasksWindow.openStage().getScene().getRoot().lookup(".list-view");
    }

    /** One entry per row: header title, or "task text" for task rows. */
    private List<String> rows() throws Exception {
        return fx(() -> {
            var out = new ArrayList<String>();
            for (var item : list().getItems()) {
                out.add(describe(item));
            }
            return out;
        });
    }

    private static String describe(Object row) {
        var s = row.toString();
        if (s.startsWith("Header[title=")) {
            return s.substring("Header[title=".length(), s.indexOf(", overdue="));
        }
        return "task:" + s.substring(s.indexOf("text=") + 5, s.indexOf(", due="));
    }

    /** Call on the FX thread. */
    private ListCell<?> cell(String text) {
        for (var n : list().getScene().getRoot().lookupAll(".list-cell")) {
            if (n instanceof ListCell<?> c && c.getItem() != null && describe(c.getItem()).equals(text)) {
                return c;
            }
        }
        throw new AssertionError("no cell " + text);
    }

    private List<Task> open() {
        return store.open();
    }

    // ---- add

    @Test
    void addWithEnter() throws Exception {
        type("write report");
        enter();
        assertEquals(List.of("write report"), open().stream().map(Task::text).toList());
        assertEquals(List.of("No date", "task:write report", "▸ Done (0)"), rows());
        assertEquals("", fx(() -> input().getText()));
        assertNull(open().get(0).due());
    }

    @Test
    void addWithButton() throws Exception {
        type("call mum");
        click(button("Add"));
        assertEquals(List.of("call mum"), open().stream().map(Task::text).toList());
        assertTrue(rows().contains("task:call mum"));
    }

    @Test
    void secondAddIsShownInTheList() throws Exception {
        type("one");
        enter();
        type("two");
        enter();
        type("three");
        click(button("Add"));
        await(() -> cell("task:three").getGraphic() != null && cell("task:one").getGraphic() != null);
        assertEquals(List.of("No date", "task:one", "task:two", "task:three", "▸ Done (0)"), rows());
    }

    @Test
    void emptyTextIsIgnored() throws Exception {
        type("   ");
        enter();
        click(button("Add"));
        assertTrue(open().isEmpty());
    }

    @Test
    void todayTomorrowNoDateButtons() throws Exception {
        click(button("Today"));
        assertEquals(LocalDate.now(), fx(() -> picker().getValue()));
        click(button("Tomorrow"));
        assertEquals(LocalDate.now().plusDays(1), fx(() -> picker().getValue()));
        type("dated");
        enter();
        assertEquals(LocalDate.now().plusDays(1), open().get(0).due());
        click(button("No date"));
        assertNull(fx(() -> picker().getValue()));
        type("undated");
        enter();
        assertEquals(2, open().size());
        assertNull(open().stream().filter(t -> t.text().equals("undated")).findFirst().orElseThrow().due());
    }

    @Test
    void typedDateInPickerEditorIsUsed() throws Exception {
        var target = LocalDate.now().plusDays(5);
        var text = fx(() -> picker().getConverter().toString(target));
        fx(() -> {
            picker().getEditor().setText(text);
            picker().getEditor().requestFocus();
            return null;
        });
        fx(() -> {
            input().requestFocus();
            return null;
        });
        await(() -> picker().getValue() != null);
        type("typed");
        enter();
        assertEquals(1, open().size());
        assertEquals(target, open().get(0).due());
    }

    // ---- grouping and title

    @Test
    void groupsIntoSectionsAndCountsInTitle() throws Exception {
        var today = LocalDate.now();
        store.add("old", today.minusDays(2));
        store.add("now", today);
        store.add("soon", today.plusDays(3));
        store.add("whenever", null);
        type("extra");
        enter();
        assertEquals(List.of("Overdue", "task:old", "Today", "task:now", "Later", "task:soon", "No date",
                "task:whenever", "task:extra", "▸ Done (0)"), rows());
        assertEquals("Tasks — 1 overdue, 1 today", fx(() -> stage().getTitle()));
    }

    @Test
    void titleHasNoCountWhenNothingIsDue() throws Exception {
        store.add("later", LocalDate.now().plusDays(2));
        type("x");
        enter();
        assertEquals("Tasks", fx(() -> stage().getTitle()));
    }

    // ---- done / undo / toggle

    @Test
    void tickDoneUntickUndoAndDoneToggle() throws Exception {
        type("finish me");
        enter();
        fire(check("task:finish me"));
        assertTrue(open().isEmpty());
        assertEquals(List.of("▸ Done (1)"), rows());

        clickCell("▸ Done (1)");
        assertEquals(List.of("▾ Done (1)", "task:finish me"), rows());

        fire(check("task:finish me"));
        assertEquals(1, open().size());
        assertEquals(List.of("No date", "task:finish me", "▾ Done (0)"), rows());

        clickCell("▾ Done (0)");
        assertEquals(List.of("No date", "task:finish me", "▸ Done (0)"), rows());
    }

    // ---- change date / delete

    @Test
    void changeDateDialog() throws Exception {
        type("move me");
        enter();
        var target = LocalDate.now().plusDays(9);
        fx(() -> {
            var item = ((ListCell<?>) cell("task:move me")).getContextMenu().getItems().get(0);
            assertEquals("Change date…", item.getText());
            Platform.runLater(item::fire);
            return null;
        });
        await(() -> dialog() != null);
        fx(() -> {
            var pane = dialog();
            ((DatePicker) pane.getContent()).setValue(target);
            ((Button) pane.lookupButton(pane.getButtonTypes().get(0))).fire();
            return null;
        });
        settle();
        assertEquals(target, open().get(0).due());
        assertEquals(List.of("Later", "task:move me", "▸ Done (0)"), rows());
    }

    @Test
    void deleteViaContextMenu() throws Exception {
        type("bye");
        enter();
        fx(() -> {
            var item = ((ListCell<?>) cell("task:bye")).getContextMenu().getItems().get(1);
            assertEquals("Delete", item.getText());
            item.fire();
            return null;
        });
        settle();
        assertTrue(open().isEmpty());
        assertEquals(List.of("▸ Done (0)"), rows());
    }

    @Test
    void deleteViaDeleteKey() throws Exception {
        type("gone");
        enter();
        fx(() -> {
            var l = list();
            l.getSelectionModel().select(1);
            l.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DELETE, false, false, false, false));
            return null;
        });
        settle();
        assertTrue(open().isEmpty());
    }

    @Test
    void deleteThenUndoPutsTheTaskBack() throws Exception {
        click(button("Tomorrow"));
        type("oops");
        enter();
        fx(() -> {
            var l = list();
            l.getSelectionModel().select(1);
            l.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DELETE, false, false, false, false));
            return null;
        });
        settle();
        assertTrue(open().isEmpty());
        assertTrue(fx(() -> stage().getScene().getRoot().lookup(".undo-bar").isVisible()));
        click(button("Undo"));
        assertEquals(List.of("oops"), open().stream().map(Task::text).toList());
        assertEquals(LocalDate.now().plusDays(1), open().get(0).due());
        assertTrue(fx(() -> !stage().getScene().getRoot().lookup(".undo-bar").isVisible()));
        assertEquals(List.of("Later", "task:oops", "▸ Done (0)"), rows());
    }

    // ---- open() twice

    @Test
    void openAgainReusesTheWindowAndShowsIt() throws Exception {
        var first = stage();
        fx(() -> {
            TasksWindow.open(null, db);
            return null;
        });
        assertSame(first, stage());
        assertTrue(fx(first::isShowing));
    }

    // ---- more helpers

    private CheckBox check(String rowText) throws Exception {
        var found = new CheckBox[1];
        await(() -> {
            try {
                var c = cell(rowText);
                found[0] = c.getGraphic() == null ? null : (CheckBox) c.getGraphic().lookup(".check-box");
            } catch (Exception | AssertionError e) {
                found[0] = null;
            }
            return found[0] != null;
        });
        return found[0];
    }

    private void fire(CheckBox box) throws Exception {
        fx(() -> {
            box.fire();
            return null;
        });
        settle();
    }

    private void clickCell(String rowText) throws Exception {
        fx(() -> {
            var c = cell(rowText);
            c.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 1, 1, 1, 1, MouseButton.PRIMARY, 1, false, false,
                    false, false, true, false, false, true, false, false, null));
            return null;
        });
        settle();
    }

    private static DialogPane dialog() {
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w.getScene() != null && w.getScene().getRoot() instanceof DialogPane p) {
                return p;
            }
        }
        return null;
    }

    @Test
    void footerHintListsTheKeysAndEscCloses() throws Exception {
        var hint = fx(() -> stage().getScene().getRoot().lookupAll(".key-hint").stream().map(n -> ((Label) n).getText()).toList());
        assertEquals(1, hint.size());
        assertTrue(hint.get(0).contains("Esc close"), hint.get(0));
        assertTrue(hint.get(0).contains("Enter add"), hint.get(0));
        var primary = fx(() -> stage().getScene().getRoot().lookupAll(".button.primary").stream().map(n -> ((Button) n).getText()).toList());
        assertEquals(1, primary.size(), primary.toString());
        assertTrue(primary.get(0).startsWith("Add"), primary.get(0));
    }
}
