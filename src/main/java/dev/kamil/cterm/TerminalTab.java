package dev.kamil.cterm;

import com.pty4j.PtyProcessBuilder;
import com.techsenger.jeditermfx.ui.DefaultHyperlinkFilter;
import com.techsenger.jeditermfx.ui.JediTermFxWidget;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import javafx.application.Platform;
import javafx.scene.control.Tab;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/** One tab = one terminal widget = one process in a pty. */
final class TerminalTab extends Tab {

    /** ESC + CR: Claude Code reads it as "new line" in the prompt (same as Option+Enter). */
    private static final String NEWLINE_IN_PROMPT = "\u001b\r";

    private final JediTermFxWidget widget;

    private TerminalTab(String title, String[] command, Path directory) {
        super(title);
        widget = new JediTermFxWidget(120, 36, new TerminalSettings());
        widget.setTtyConnector(new PtyTtyConnector(startProcess(command, directory), title));
        widget.addHyperlinkFilter(new DefaultHyperlinkFilter());
        widget.addListener(w -> Platform.runLater(this::closeTab));
        widget.getPane().addEventFilter(KeyEvent.ANY, this::shiftEnterAsNewline);
        setContent(widget.getPane());
        setOnClosed(e -> widget.close());
        setOnSelectionChanged(e -> {
            if (isSelected()) {
                focus();
            }
        });
        widget.start();
    }

    static TerminalTab shell(Path directory) {
        return new TerminalTab("shell", new String[]{shellPath(), "--login"}, directory);
    }

    static TerminalTab claude(Path directory) {
        var name = directory.getFileName() == null ? "/" : directory.getFileName().toString();
        // When Claude exits, the tab stays open as a login shell in the same directory.
        var script = "claude; exec " + shellPath() + " --login";
        return new TerminalTab("claude: " + name, new String[]{shellPath(), "--login", "-c", script}, directory);
    }

    void focus() {
        Platform.runLater(() -> widget.getPreferredFocusableNode().requestFocus());
    }

    private void shiftEnterAsNewline(KeyEvent event) {
        if (!event.isShiftDown() || event.isControlDown() || event.isAltDown()) {
            return;
        }
        if (event.getEventType() == KeyEvent.KEY_PRESSED && event.getCode() == KeyCode.ENTER) {
            try {
                widget.getTtyConnector().write(NEWLINE_IN_PROMPT);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            event.consume();
        } else if (event.getEventType() == KeyEvent.KEY_TYPED && "\r".equals(event.getCharacter())) {
            event.consume();
        }
    }

    private void closeTab() {
        var tabPane = getTabPane();
        if (tabPane != null) {
            tabPane.getTabs().remove(this);
        }
    }

    private static com.pty4j.PtyProcess startProcess(String[] command, Path directory) {
        var env = new HashMap<>(System.getenv());
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("TERM_PROGRAM", "claude-terminal-fx");
        // Started from inside a Claude Code session, these would make claude think it is nested.
        env.remove("CLAUDECODE");
        env.remove("CLAUDE_CODE_ENTRYPOINT");
        try {
            return new PtyProcessBuilder()
                    .setCommand(command)
                    .setEnvironment(env)
                    .setDirectory(directory.toString())
                    .setInitialColumns(120)
                    .setInitialRows(36)
                    .setConsole(false)
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start " + String.join(" ", command), e);
        }
    }

    private static String shellPath() {
        var shell = System.getenv("SHELL");
        return shell == null || shell.isBlank() ? "/bin/bash" : shell;
    }
}
