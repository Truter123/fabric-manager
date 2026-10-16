package dev.kamil.fabric.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.techsenger.jeditermfx.core.TtyConnector;
import com.techsenger.jeditermfx.ui.JediTermFxWidget;
import com.techsenger.jeditermfx.ui.settings.DefaultSettingsProvider;
import dev.kamil.fabric.session.Account;
import dev.kamil.fabric.session.Tool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.TabPane;
import javafx.scene.input.ScrollEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real JavaFX terminals: needs a display, skipped without one. */
class TerminalFxTest {

    @BeforeAll
    static void startFx() {
        Assumptions.assumeTrue(System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null,
                "no display");
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // another test class started it
        }
        Platform.setImplicitExit(false);
    }

    @Test
    void scrolledUpViewKeepsItsLinesWhileOutputArrives() throws Exception {
        var term = FakeTerminal.show(new TerminalSettings());
        term.print(300);
        term.scrollUp(10);
        var top = term.topLine();
        term.printMore(5);
        assertEquals(top, term.settledTopLine(top));
        term.close();
    }

    @Test
    void scrolledUpViewKeepsItsLinesWhenScrollbackIsFull() throws Exception {
        var term = FakeTerminal.show(new DefaultSettingsProvider() {
            @Override
            public int getBufferMaxLinesCount() {
                return 100;
            }
        });
        term.print(300);
        term.scrollUp(10);
        var top = term.topLine();
        term.printMore(5);
        // The oldest lines fall out of a full scrollback; the view follows its lines until they are gone.
        assertEquals(top, term.settledTopLine(top));
        term.close();
    }

    @Test
    void wheelInFullscreenAppSendsOnlyTheMouseReport() throws Exception {
        var term = FakeTerminal.show(new TerminalSettings());
        // Claude Code fullscreen: alternate screen, all-motion mouse reporting, SGR coordinates.
        term.send("\u001b[?1049h\u001b[?1003h\u001b[?1006h");
        var sent = term.wheelUp();
        // SGR button 64 is wheel up; 65 would scroll the app the other way.
        assertTrue(sent.startsWith("\u001b[<64;"), "wheel-up report expected, got " + printable(sent));
        // An arrow key would also step through Claude's prompt history.
        assertFalse(sent.contains("\u001b[A") || sent.contains("\u001bOA"), printable(sent));
        term.close();
    }

    @Test
    void wheelInPagerWithoutMouseSendsArrowKeys() throws Exception {
        var term = FakeTerminal.show(new TerminalSettings());
        // less, man: alternate screen, no mouse reporting.
        term.send("\u001b[?1049h");
        var sent = term.wheelUp();
        assertTrue(sent.contains("\u001b[A") || sent.contains("\u001bOA"), printable(sent));
        term.close();
    }

    private static String printable(String text) {
        return text.replace("\u001b", "ESC");
    }

    @Test
    void switchingTabsFocusesTheTerminal(@TempDir Path home) throws Exception {
        var dir = Files.createDirectories(home.resolve("project"));
        var tabs = onFx(() -> {
            var pane = new TabPane(TerminalTab.open(Tool.SHELL, dir, Account.personal(home)),
                    TerminalTab.open(Tool.SHELL, dir, Account.personal(home)));
            var stage = new Stage();
            stage.setScene(new Scene(pane, 600, 400));
            stage.show();
            return pane;
        });
        onFx(() -> {
            tabs.getSelectionModel().select(1);
            return null;
        });
        waitFor(() -> call(() -> isInside(tabs.getScene().getFocusOwner(), tabs.getTabs().get(1).getContent())));
        var owner = onFx(() -> tabs.getScene().getFocusOwner());
        assertTrue(owner instanceof Canvas, "focus owner: " + owner);
        onFx(() -> {
            tabs.getTabs().forEach(t -> ((TerminalTab) t).requestClose());
            tabs.getScene().getWindow().hide();
            return null;
        });
    }

    @Test
    void duplicateOpensAFreshSessionWithTheSameToolFolderAndAccount(@TempDir Path home) throws Exception {
        var dir = Files.createDirectories(home.resolve("project"));
        var account = new Account("work", home.resolve(".claude-work"));
        var tabs = onFx(() -> {
            var pane = new TabPane(TerminalTab.open(Tool.SHELL, dir, account));
            pane.getTabs().add(((TerminalTab) pane.getTabs().get(0)).duplicate());
            return pane;
        });
        var original = (TerminalTab) tabs.getTabs().get(0);
        var copy = (TerminalTab) tabs.getTabs().get(1);
        assertEquals(original.tool(), copy.tool());
        assertEquals(original.directory(), copy.directory());
        assertEquals(original.account(), copy.account());
        assertEquals(original.getText(), copy.getText());
        assertNotEquals(original.sessionId(), copy.sessionId(), "a copy must not share the Claude session");
        onFx(() -> {
            tabs.getTabs().forEach(t -> ((TerminalTab) t).requestClose());
            return null;
        });
    }

    private static boolean isInside(Node node, Node parent) {
        for (var n = node; n != null; n = n.getParent()) {
            if (n == parent) {
                return true;
            }
        }
        return false;
    }

    /** A widget fed from a queue instead of a pty. */
    private record FakeTerminal(JediTermFxWidget widget, LinkedBlockingQueue<String> output, StringBuffer input,
            Stage stage) {

        static FakeTerminal show(DefaultSettingsProvider settings) throws Exception {
            var output = new LinkedBlockingQueue<String>();
            var input = new StringBuffer();
            return onFx(() -> {
                var widget = new JediTermFxWidget(80, 24, settings);
                widget.setTtyConnector(new QueueConnector(output, input));
                widget.start();
                var stage = new Stage();
                stage.setScene(new Scene(widget.getPane(), 800, 500));
                stage.show();
                return new FakeTerminal(widget, output, input, stage);
            });
        }

        void print(int lines) throws Exception {
            for (int i = 1; i <= lines; i++) {
                output.put("line " + i + "\r\n");
            }
            waitFor(() -> output.isEmpty() && call(() -> widget.getTerminalTextBuffer().getHistoryLinesCount() > 0));
            Thread.sleep(300);
        }

        void printMore(int lines) throws Exception {
            for (int i = 1; i <= lines; i++) {
                output.put("more " + i + "\r\n");
                // One redraw per line, as when Claude streams.
                Thread.sleep(150);
            }
        }

        void send(String escapes) throws Exception {
            output.put(escapes);
            waitFor(output::isEmpty);
            Thread.sleep(300);
        }

        /** One wheel notch up over the terminal, as the mouse sends it; returns what reached the app. */
        String wheelUp() throws Exception {
            input.setLength(0);
            onFx(() -> {
                widget.getTerminalPanel().getCanvas().fireEvent(new ScrollEvent(ScrollEvent.SCROLL, 100, 100, 100, 100,
                        false, false, false, false, false, false, 0, 40, 0, 40,
                        ScrollEvent.HorizontalTextScrollUnits.NONE, 0, ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0,
                        null));
                return null;
            });
            Thread.sleep(200);
            return input.toString();
        }

        void scrollUp(int lines) throws Exception {
            onFx(() -> {
                for (int i = 0; i < lines; i++) {
                    widget.getTerminalPanel().scrollUp();
                }
                return null;
            });
            Thread.sleep(200);
        }

        /**
         * The top line once the redraw timer has applied the last scroll; shifts land on a later redraw than the
         * output (JavaFX 27 more so than 21). Returns the last value seen when {@code expected} does not show up.
         */
        String settledTopLine(String expected) throws Exception {
            long end = System.currentTimeMillis() + 3_000;
            var line = topLine();
            while (!line.equals(expected) && System.currentTimeMillis() < end) {
                Thread.sleep(50);
                line = topLine();
            }
            return line;
        }

        String topLine() throws Exception {
            return onFx(() -> {
                var origin = TerminalPanelAccess.origin(widget);
                return widget.getTerminalTextBuffer().getLine(origin).getText().trim();
            });
        }

        void close() throws Exception {
            onFx(() -> {
                stage.hide();
                widget.close();
                return null;
            });
        }
    }

    private static final class TerminalPanelAccess {

        static int origin(JediTermFxWidget widget) throws Exception {
            var field = widget.getTerminalPanel().getClass().getDeclaredField("swingClientScrollOrigin");
            field.setAccessible(true);
            return field.getInt(widget.getTerminalPanel());
        }
    }

    private static final class QueueConnector implements TtyConnector {

        private final LinkedBlockingQueue<String> output;
        private final StringBuffer input;
        private String pending = "";

        QueueConnector(LinkedBlockingQueue<String> output, StringBuffer input) {
            this.output = output;
            this.input = input;
        }

        @Override
        public int read(char[] buf, int offset, int length) throws java.io.IOException {
            try {
                if (pending.isEmpty()) {
                    pending = output.take();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
            int n = Math.min(length, pending.length());
            pending.getChars(0, n, buf, offset);
            pending = pending.substring(n);
            return n;
        }

        @Override
        public void write(byte[] bytes) {
            input.append(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public void write(String string) {
            input.append(string);
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean ready() {
            return !pending.isEmpty() || !output.isEmpty();
        }

        @Override
        public String getName() {
            return "fake";
        }

        @Override
        public void close() {
        }
    }

    private static <T> T onFx(Callable<T> action) throws Exception {
        var result = new CompletableFuture<T>();
        Platform.runLater(() -> {
            try {
                result.complete(action.call());
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    private static boolean call(Callable<Boolean> check) {
        try {
            return onFx(check);
        } catch (Exception e) {
            return false;
        }
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("timed out");
            }
            Thread.sleep(50);
        }
    }
}
