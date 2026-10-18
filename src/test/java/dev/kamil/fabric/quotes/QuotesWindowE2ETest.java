package dev.kamil.fabric.quotes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.EscapeClosesWindowsE2ETest;
import dev.kamil.fabric.FxHarness;
import dev.kamil.fabric.session.Database;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.Arguments;

/** Drives the real Quotes window controls and its dialogs against a temporary H2 database. */
class QuotesWindowE2ETest {

    @TempDir
    Path dir;

    private QuoteStore store;
    private final AtomicInteger changes = new AtomicInteger();

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
        var db = Database.at(dir.resolve("e2e")).migrate();
        store = new QuoteStore(db);
        store.add("Seeded", "Ann");
        fx(() -> {
            QuotesWindow.open(null, db, changes::incrementAndGet);
            return null;
        });
        await(() -> cells().size() == 1);
    }

    @AfterEach
    void close() throws Exception {
        fx(() -> {
            for (var w : new ArrayList<>(Window.getWindows())) {
                if (w.getScene() != null && w.getScene().getRoot() instanceof DialogPane) {
                    w.hide();
                }
            }
            var stage = QuotesWindow.openStage();
            if (stage != null) {
                stage.hide();
            }
            return null;
        });
    }

    /** The case of {@link EscapeClosesWindowsE2ETest}. */
    static Stream<Arguments> escapeCase() {
        return Stream.of(EscapeClosesWindowsE2ETest.escapeCase("Quotes", dir -> {
            var db = Database.at(dir.resolve("escape")).migrate();
            return FxHarness.fx(() -> {
                QuotesWindow.open(null, db, () -> { });
                return QuotesWindow.openStage();
            });
        }, () -> FxHarness.fx(() -> {
            var stage = QuotesWindow.openStage();
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
        return QuotesWindow.openStage().getScene().getRoot();
    }

    /** Call on the FX thread. */
    private static Button button(String text) {
        for (var n : root().lookupAll(".button")) {
            if (n instanceof Button b && text.equals(b.getText())) {
                return b;
            }
        }
        throw new AssertionError("no button " + text);
    }

    @SuppressWarnings("unchecked")
    private static ListView<Quote> list() {
        return (ListView<Quote>) root().lookup(".list-view");
    }

    /** Call on the FX thread: the non-empty cells' text. */
    private static List<ListCell<?>> cells() {
        var out = new ArrayList<ListCell<?>>();
        for (var n : list().lookupAll(".list-cell")) {
            if (n instanceof ListCell<?> c && c.getText() != null) {
                out.add(c);
            }
        }
        return out;
    }

    private static List<String> texts() {
        return cells().stream().map(ListCell::getText).toList();
    }

    /** Fires without waiting: a button that opens a modal dialog never returns until it closes. */
    private static void fireLater(Runnable action) {
        Platform.runLater(action);
    }

    /** Call on the FX thread. */
    private static DialogPane dialog() {
        for (var w : Window.getWindows()) {
            if (w.isShowing() && w.getScene() != null && w.getScene().getRoot() instanceof DialogPane p) {
                return p;
            }
        }
        return null;
    }

    private static void press(ButtonType type) throws Exception {
        await(() -> dialog() != null);
        fx(() -> {
            ((Button) dialog().lookupButton(type)).fire();
            return null;
        });
        await(() -> dialog() == null);
    }

    private static void pressNamed(String name) throws Exception {
        await(() -> dialog() != null);
        fx(() -> {
            for (var t : dialog().getButtonTypes()) {
                if (name.equals(t.getText())) {
                    ((Button) dialog().lookupButton(t)).fire();
                    return null;
                }
            }
            throw new AssertionError("no dialog button " + name);
        });
        await(() -> dialog() == null);
    }

    private static void fill(String quote, String author) throws Exception {
        await(() -> dialog() != null);
        fx(() -> {
            ((TextArea) dialog().lookup(".text-area")).setText(quote);
            ((TextField) dialog().lookup(".text-field")).setText(author);
            return null;
        });
    }

    private void select(int index) throws Exception {
        fx(() -> {
            list().getSelectionModel().select(index);
            return null;
        });
    }

    // ---- tests

    @Test
    void rowsShowQuoteAndAuthor() throws Exception {
        assertEquals(List.of("“Seeded” — Ann"), fx(QuotesWindowE2ETest::texts));
    }

    @Test
    void editAndDeleteAreDisabledWithoutSelection() throws Exception {
        assertTrue(fx(() -> button("Edit…").isDisabled() && button("Delete…").isDisabled()));
        select(0);
        assertFalse(fx(() -> button("Edit…").isDisabled() || button("Delete…").isDisabled()));
    }

    @Test
    void addQuoteWithAuthor() throws Exception {
        fireLater(() -> button("Add…").fire());
        fill("  Be water  ", " Bruce ");
        press(ButtonType.OK);
        await(() -> cells().size() == 2);
        assertEquals(2, store.all().size());
        assertTrue(store.all().stream().anyMatch(q -> q.text().equals("Be water") && "Bruce".equals(q.author())));
        assertEquals(1, changes.get());
        assertTrue(fx(QuotesWindowE2ETest::texts).contains("“Be water” — Bruce"));
    }

    @Test
    void addQuoteWithoutAuthorShowsNoDash() throws Exception {
        fireLater(() -> button("Add…").fire());
        fill("Anonymous words", "");
        press(ButtonType.OK);
        await(() -> cells().size() == 2);
        assertTrue(fx(QuotesWindowE2ETest::texts).contains("“Anonymous words”"));
    }

    @Test
    void addSeveralKeepsAllRowsRendered() throws Exception {
        for (int i = 0; i < 4; i++) {
            fireLater(() -> button("Add…").fire());
            fill("q" + i, "a" + i);
            press(ButtonType.OK);
            int expected = i + 2;
            await(() -> cells().size() == expected);
        }
        assertEquals(5, store.all().size());
    }

    @Test
    void cancelAndBlankTextAddNothing() throws Exception {
        fireLater(() -> button("Add…").fire());
        fill("something", "x");
        press(ButtonType.CANCEL);
        fireLater(() -> button("Add…").fire());
        fill("   ", "x");
        assertTrue(fx(() -> dialog().lookupButton(ButtonType.OK).isDisabled()));
        press(ButtonType.CANCEL);
        assertEquals(1, store.all().size());
        assertEquals(0, changes.get());
    }

    @Test
    void okIsDisabledWhileTheQuoteTextIsBlank() throws Exception {
        fireLater(() -> button("Add…").fire());
        await(() -> dialog() != null);
        assertTrue(fx(() -> dialog().lookupButton(ButtonType.OK).isDisabled()), "empty quote");
        fill("   ", "Ann");
        assertTrue(fx(() -> dialog().lookupButton(ButtonType.OK).isDisabled()), "blank quote with an author");
        fill("Something", "Ann");
        assertFalse(fx(() -> dialog().lookupButton(ButtonType.OK).isDisabled()), "quote typed");
        press(ButtonType.CANCEL);
    }

    @Test
    void editButtonPrefillsAndUpdates() throws Exception {
        select(0);
        fireLater(() -> button("Edit…").fire());
        await(() -> dialog() != null);
        assertEquals("Seeded", fx(() -> ((TextArea) dialog().lookup(".text-area")).getText()));
        assertEquals("Ann", fx(() -> ((TextField) dialog().lookup(".text-field")).getText()));
        fill("Changed", "Zed");
        press(ButtonType.OK);
        await(() -> texts().equals(List.of("“Changed” — Zed")));
        assertEquals("Changed", store.all().get(0).text());
        assertEquals(1, changes.get());
    }

    @Test
    void doubleClickOnARowOpensEdit() throws Exception {
        select(0); // a real double click selects the row on the first press
        fireLater(() -> {
            var cell = cells().get(0);
            cell.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 1, 1, 1, 1, MouseButton.PRIMARY, 2, false,
                    false, false, false, true, false, false, true, false, false, null));
        });
        await(() -> dialog() != null);
        assertEquals("Edit quote", fx(() -> ((javafx.stage.Stage) dialog().getScene().getWindow()).getTitle()));
        press(ButtonType.CANCEL);
    }

    @Test
    void deleteAsksForConfirmation() throws Exception {
        select(0);
        fireLater(() -> button("Delete…").fire());
        press(ButtonType.CANCEL);
        assertEquals(1, store.all().size());
        fireLater(() -> button("Delete…").fire());
        pressNamed("Delete");
        await(() -> cells().isEmpty());
        assertTrue(store.all().isEmpty());
        assertEquals(1, changes.get());
        assertEquals("No quotes yet. Click Add to write your first one.",
                fx(() -> ((javafx.scene.control.Label) list().getPlaceholder()).getText()));
        assertTrue(fx(() -> button("Edit…").isDisabled()));
    }

    @Test
    void openAgainReusesTheWindow() throws Exception {
        var first = fx(QuotesWindow::openStage);
        fx(() -> {
            QuotesWindow.open(null, null, null);
            return null;
        });
        assertSame(first, fx(QuotesWindow::openStage));
    }

    @Test
    void footerHintListsTheKeysAndEscCloses() throws Exception {
        var hint = fx(() -> root().lookupAll(".key-hint").stream().map(n -> ((Label) n).getText()).toList());
        assertEquals(1, hint.size());
        assertTrue(hint.get(0).contains("Esc close"), hint.get(0));
        assertTrue(hint.get(0).contains("Double-click edit"), hint.get(0));
        var primary = fx(() -> root().lookupAll(".button.primary").stream().map(n -> ((Button) n).getText()).toList());
        assertEquals(1, primary.size(), primary.toString());
        assertTrue(primary.get(0).startsWith("Add…"), primary.get(0));
    }
}
