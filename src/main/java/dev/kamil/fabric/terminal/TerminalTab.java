package dev.kamil.fabric.terminal;

import dev.kamil.fabric.Dialogs;
import dev.kamil.fabric.git.GitBranch;
import dev.kamil.fabric.claude.ClaudeEvents;
import dev.kamil.fabric.session.Account;
import dev.kamil.fabric.session.SavedSession;
import dev.kamil.fabric.session.SessionSidebar;
import dev.kamil.fabric.session.Tool;
import com.pty4j.PtyProcessBuilder;
import com.techsenger.jeditermfx.ui.DefaultHyperlinkFilter;
import com.techsenger.jeditermfx.ui.JediTermFxWidget;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/** One tab = one terminal widget = one process in a pty. */
public final class TerminalTab extends Tab {

    /** Coarse state of the tool inside the tab, shown as a dot in the sidebar. */
    public enum Status { TOOL_RUNNING, SHELL, EXITED }

    /** What a Claude session reported through its hooks (see ClaudeEvents). */
    public enum Activity { WORKING, WAITING, DONE, ERROR }

    /** ESC + CR: Claude Code reads it as "new line" in the prompt (same as Option+Enter). */
    /** Output newer than this counts as "working" for tools that report no hooks. */
    static final long BUSY_NANOS = 2_000_000_000L;
    /** Value of the last-output stamp when nothing has been printed yet. */
    static final long NEVER = Long.MIN_VALUE;
    private static final Color EXITED_COLOR = Color.web("#d96b75");

    private static final String NEWLINE_IN_PROMPT = "\u001b\r";
    /** CSI Z: back-tab. JavaFX would use Shift+Tab for focus traversal, so the tool never sees it otherwise. */
    private static final String BACK_TAB = "\u001b[Z";

    private final JediTermFxWidget widget;
    private final PtyTtyConnector connector;
    private final Pane main;
    /** The shell beside the session, or null while the tab is not split. */
    private Pane second;
    private SplitPane split;
    private boolean secondFocused;
    private final Tool tool;
    private final Path directory;
    private final Account account;
    private final WheelScroll wheel = new WheelScroll();
    private final String defaultTitle;
    private boolean released;
    private final String sessionId;
    private final Lifecycle lifecycle;
    private String name = "";
    private Activity activity;
    private boolean unseen;
    private final Circle dot = new Circle(4);
    private Timeline outputWatch;
    private volatile Status cachedStatus = Status.SHELL;
    private volatile String cachedBranch;

    /** One terminal in the tab: the widget and the process behind it. */
    private record Pane(JediTermFxWidget widget, PtyTtyConnector connector) {
    }

    /** Callbacks for the session record; the app wires them to the SessionStore. */
    public interface Lifecycle {
        Lifecycle NONE = new Lifecycle() { };

        default void started(String sessionId, Path directory, Tool tool, Account account, String title) {
        }

        default void renamed(String sessionId, String title) {
        }

        default void ended(String sessionId) {
        }

        /** The tab's derived activity changed (non-Claude tabs); views should redraw. */
        default void activityChanged(String sessionId) {
        }
    }

    /** Session id and the shell command that starts the tool, or null command for a plain shell. */
    record Launch(String sessionId, String command) {
    }

    private TerminalTab(String title, String[] command, Tool tool, Path directory, Account account,
            String sessionId, Lifecycle lifecycle) {
        super(title);
        defaultTitle = title;
        this.lifecycle = lifecycle;
        this.sessionId = sessionId;
        this.tool = tool;
        this.directory = directory;
        this.account = account;
        main = createPane(command, title, sessionId, () -> Platform.runLater(() -> {
            updateDot();
            if (tool.isSsh()) {
                showDisconnected();
            } else {
                closeTab();
            }
        }));
        widget = main.widget();
        connector = main.connector();
        setContent(widget.getPane());
        dot.setFill(dotColor(null, false));
        setGraphic(dot);
        setOnCloseRequest(e -> {
            if (!confirmClose()) {
                e.consume();
            }
        });
        setOnClosed(e -> release());
        setOnSelectionChanged(e -> {
            if (isSelected()) {
                setUnseen(false);
                focusActive();
            }
        });
        widget.start();
        if (!tool.name().equals("claude")) {
            outputWatch = new Timeline(new KeyFrame(Duration.seconds(1), e -> refreshOutputActivity()));
            outputWatch.setCycleCount(Timeline.INDEFINITE);
            outputWatch.play();
        }
    }

    /** Derives the activity of a hook-less tool from its pty output and tells the views when it changed. */
    private void refreshOutputActivity() {
        var derived = outputActivity(connector.lastOutputNanos(), System.nanoTime());
        if (derived != activity) {
            setActivity(derived);
            lifecycle.activityChanged(sessionId);
        } else {
            updateDot();
        }
    }

    /** WORKING while output arrived within the last 2 s, DONE (idle) after, null if the tool never printed. */
    static Activity outputActivity(long lastOutputNanos, long nowNanos) {
        if (lastOutputNanos == NEVER) {
            return null;
        }
        return nowNanos - lastOutputNanos <= BUSY_NANOS ? Activity.WORKING : Activity.DONE;
    }

    /** Tab dot colour: red when the process exited, else the activity colour of the sidebar. */
    static Color dotColor(Activity activity, boolean exited) {
        return exited ? EXITED_COLOR : SessionSidebar.runningColor(activity);
    }

    private void updateDot() {
        dot.setFill(dotColor(activity, !alive()));
    }

    static TerminalTab open(Tool tool, Path directory, Account account) {
        return open(tool, directory, account, Lifecycle.NONE);
    }

    public static TerminalTab open(Tool tool, Path directory, Account account, Lifecycle lifecycle) {
        var id = UUID.randomUUID().toString();
        return start(tool, directory, account, id,
                tool.isShell() ? null : launchCommand(tool, ClaudeEvents.pluginDir(), id), null, lifecycle);
    }

    /** Reopens a saved session: claude continues the same conversation, other tools start fresh in the folder. */
    public static TerminalTab resume(SavedSession saved, Lifecycle lifecycle) {
        var launch = resumeLaunch(saved, ClaudeEvents.pluginDir());
        return start(saved.tool(), saved.project(), saved.account(), launch.sessionId(), launch.command(),
                launch.sessionId().equals(saved.id()) ? saved.title() : null, lifecycle);
    }

    private static TerminalTab start(Tool tool, Path directory, Account account, String id, String toolCommand, String savedTitle,
            Lifecycle lifecycle) {
        var name = directory.getFileName() == null ? "/" : directory.getFileName().toString();
        var shell = shellPath();
        TerminalTab tab;
        if (toolCommand == null) {
            tab = new TerminalTab("shell: " + name, new String[]{shell, "--login"}, tool, directory, account, id,
                    lifecycle);
        } else {
            var script = launchScript(tool, toolCommand, shell);
            var label = account.isPersonal() ? tool.name() : tool.name() + "@" + account.name();
            tab = new TerminalTab(label + ": " + name, new String[]{shell, "--login", "-c", script},
                    tool, directory, account, id, lifecycle);
        }
        if (savedTitle != null && !savedTitle.equals(tab.defaultTitle)) {
            tab.name = savedTitle;
            tab.setText(tab.displayName());
        }
        lifecycle.started(id, directory, tool, account, tab.displayName());
        return tab;
    }

    /**
     * Script for {@code $SHELL --login -c}. Most tools leave the tab as a login shell in the same directory when
     * they exit; ssh does not, because the tab then offers to reconnect instead.
     */
    static String launchScript(Tool tool, String toolCommand, String shell) {
        return tool.isSsh() ? toolCommand : toolCommand + "; exec " + shell + " --login";
    }

    /** The ssh connection ended: keep the tab and offer Reconnect (Enter) or Close (Esc) over the dead terminal. */
    private void showDisconnected() {
        var title = new Label("Disconnected from " + tool.name().replaceFirst("^ssh ", ""));
        title.getStyleClass().add("disconnected-title");
        var reconnect = new Button("Reconnect");
        reconnect.setDefaultButton(true);
        reconnect.setOnAction(e -> reconnect());
        var close = new Button("Close");
        close.setCancelButton(true);
        close.setOnAction(e -> closeTab());
        var buttons = new HBox(8, reconnect, close);
        buttons.setAlignment(Pos.CENTER);
        var box = new VBox(12, title, buttons);
        box.setAlignment(Pos.CENTER);
        box.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        box.getStyleClass().add("disconnected-panel");
        var overlay = new StackPane(widget.getPane(), box);
        setContent(overlay);
        Platform.runLater(reconnect::requestFocus);
    }

    /** Starts the same command in a fresh tab next to this one (the pane of a tab cannot be swapped) and closes this. */
    private void reconnect() {
        var tabPane = getTabPane();
        if (tabPane == null) {
            return;
        }
        var fresh = duplicate();
        var index = tabPane.getTabs().indexOf(this);
        tabPane.getTabs().add(index + 1, fresh);
        tabPane.getSelectionModel().select(fresh);
        closeTab();
    }

    /**
     * What to run to resume a saved session. Claude with a transcript on disk gets {@code --resume <id>};
     * claude without one, and every other tool, gets a fresh session id.
     */
    static Launch resumeLaunch(SavedSession saved, Path pluginDir) {
        var tool = saved.tool();
        if (tool.isShell()) {
            return new Launch(UUID.randomUUID().toString(), null);
        }
        if (!tool.name().equals("claude")) {
            return new Launch(UUID.randomUUID().toString(), tool.command());
        }
        if (hasTranscript(saved.account().configDir(), saved.id())) {
            return new Launch(saved.id(), tool.command() + " --plugin-dir " + shellQuote(pluginDir.toString())
                    + " --resume " + shellQuote(saved.id()));
        }
        var fresh = UUID.randomUUID().toString();
        return new Launch(fresh, launchCommand(tool, pluginDir, fresh));
    }

    /** True when claude keeps a transcript for the id in any folder under its projects directory. */
    static boolean hasTranscript(Path claudeHome, String sessionId) {
        var projects = claudeHome.resolve("projects");
        if (!Files.isDirectory(projects)) {
            return false;
        }
        try (var dirs = Files.newDirectoryStream(projects)) {
            for (var dir : dirs) {
                if (Files.isRegularFile(dir.resolve(sessionId + ".jsonl"))) {
                    return true;
                }
            }
        } catch (IOException e) {
            System.err.println("Cannot look for the transcript of " + sessionId + ": " + e.getMessage());
        }
        return false;
    }

    /** Command line to run the tool; claude gets the FabricManager plugin and the session id it is tracked by. */
    static String launchCommand(Tool tool, Path pluginDir, String sessionId) {
        if (!tool.name().equals("claude")) {
            return tool.command();
        }
        return tool.command() + " --plugin-dir " + shellQuote(pluginDir.toString())
                + " --session-id " + shellQuote(sessionId);
    }

    /** Single-quotes text for a POSIX shell; a single quote inside becomes '\''. */
    public static String shellQuote(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    public String sessionId() {
        return sessionId;
    }

    /** Last state reported by the tool's hooks, or null. */
    public Activity activity() {
        return activity;
    }

    public void setActivity(Activity activity) {
        this.activity = activity;
        updateDot();
    }

    private boolean pinned;

    public boolean pinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    /** True when the tab needs attention that the user has not looked at yet. */
    public boolean unseen() {
        return unseen;
    }

    public void setUnseen(boolean unseen) {
        this.unseen = unseen;
        setText(unseenPrefix(unseen) + displayName());
    }

    static String unseenPrefix(boolean unseen) {
        return unseen ? "\u25cf " : "";
    }

    /** Title without the unseen marker. */
    public String displayName() {
        return titleFor(name, defaultTitle);
    }

    /** Sequence to send to the pty for a key JavaFX would otherwise keep for itself, or null. */
    static String sequenceFor(KeyCode code, boolean shift, boolean ctrl, boolean alt, boolean meta) {
        if (ctrl || alt || meta) {
            return null;
        }
        if (code == KeyCode.ENTER && shift) {
            return NEWLINE_IN_PROMPT;
        }
        if (code == KeyCode.TAB) {
            return shift ? BACK_TAB : "\t";
        }
        return null;
    }

    public Tool tool() {
        return tool;
    }

    public Account account() {
        return account;
    }

    public Path directory() {
        return directory;
    }

    /** A new session with this tab's tool, folder and account; it starts fresh, under its own session id. */
    public TerminalTab duplicate() {
        return open(tool, directory, account, lifecycle);
    }

    /** Name the user gave this session, or "" when it has none. */
    public String name() {
        return name;
    }

    /** Sets the session name; a blank name restores the default title ("claude: folder"). */
    public void rename(String newName) {
        name = newName == null ? "" : newName.strip();
        setText(unseenPrefix(unseen) + displayName());
        lifecycle.renamed(sessionId, displayName());
    }

    static String titleFor(String name, String defaultTitle) {
        return name.isBlank() ? defaultTitle : name;
    }

    long pid() {
        return connector.pid();
    }

    public boolean alive() {
        return connector.isConnected();
    }

    /** First word of the tool's command, computed once on first use. */
    private volatile String toolWord;

    /** Status from the last {@link #sample()}; cheap, safe on the FX thread. */
    public Status cachedStatus() {
        return cachedStatus;
    }

    /** Git branch from the last {@link #sample()}, or null. */
    public String cachedBranch() {
        return cachedBranch;
    }

    /** Recomputes status and branch (process-table and file reads): call off the FX thread. */
    public void sample() {
        cachedStatus = status();
        cachedBranch = GitBranch.of(directory).orElse(null);
    }

    /** Best effort: never throws. */
    public Status status() {
        try {
            if (!alive()) {
                return Status.EXITED;
            }
            if (tool.isShell()) {
                return Status.SHELL;
            }
            var cached = toolWord;
            if (cached == null) {
                cached = tool.command().trim().split("\\s+")[0];
                toolWord = cached;
            }
            final var word = cached;
            var handle = ProcessHandle.of(pid());
            if (handle.isPresent() && handle.get().descendants().anyMatch(p -> mentions(p, word))) {
                return Status.TOOL_RUNNING;
            }
        } catch (RuntimeException e) {
            // fall through
        }
        return alive() ? Status.SHELL : Status.EXITED;
    }

    private static boolean mentions(ProcessHandle process, String word) {
        var info = process.info();
        return info.commandLine().or(info::command).map(c -> c.contains(word)).orElse(false);
    }

    /** Asks first when the tool is still running, so one stray click does not end a session. */
    boolean confirmClose() {
        if (status() != Status.TOOL_RUNNING || getTabPane() == null) {
            return true;
        }
        return Dialogs.confirm(getTabPane().getScene().getWindow(), "Close " + displayName() + "?",
                tool.name() + " is still running in this tab. Closing the tab ends it.", "Close tab");
    }

    /** Closes the tab like its close button does, including the question. */
    public void requestClose() {
        if (getTabPane() != null && confirmClose()) {
            getTabPane().getTabs().remove(this);
            release();
        }
    }

    /**
     * Frees the terminal and the session's event file once. Removing a tab from code does not fire
     * onClosed, so every close path calls this.
     */
    private void release() {
        if (released) {
            return;
        }
        released = true;
        if (outputWatch != null) {
            outputWatch.stop();
        }
        lifecycle.ended(sessionId);
        if (second != null) {
            second.widget().close();
            second = null;
        }
        widget.close();
        try {
            Files.deleteIfExists(ClaudeEvents.eventsDir().resolve(sessionId));
        } catch (IOException ex) {
            // best effort
        }
    }

    public void focus() {
        // The canvas, not getPreferredFocusableNode(): that is the pane around it, and the key handlers sit on the canvas.
        Platform.runLater(() -> widget.getTerminalPanel().getCanvas().requestFocus());
    }

    /** Types text into the tool or shell; one trailing newline becomes CR, which submits. */
    public void send(String text) {
        var out = text.endsWith("\n") ? text.substring(0, text.length() - 1) + "\r" : text;
        try {
            widget.getTtyConnector().write(out);
        } catch (IOException e) {
            // the process is gone; the tab closes on its own
        }
    }

    /** Widget plus pty process with the filters and key handling every pane of the tab gets; not started yet. */
    private Pane createPane(String[] command, String title, String id, Runnable onExit) {
        var w = new JediTermFxWidget(120, 36, new TerminalSettings());
        var c = new PtyTtyConnector(startProcess(command, directory, account, id), title);
        var pane = new Pane(w, c);
        w.setTtyConnector(c);
        w.addHyperlinkFilter(new DefaultHyperlinkFilter());
        w.addHyperlinkFilter(new FilePathLinks(directory));
        w.addListener(x -> onExit.run());
        w.getPane().addEventFilter(KeyEvent.ANY, e -> forwardKeys(pane, e));
        var canvas = w.getTerminalPanel().getCanvas();
        canvas.addEventFilter(ScrollEvent.SCROLL, e -> scrollBack(pane, e));
        canvas.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> canvas.requestFocus());
        canvas.focusedProperty().addListener((o, was, now) -> {
            if (now) {
                secondFocused = pane == second;
            }
        });
        return pane;
    }

    static Orientation toggled(Orientation orientation) {
        return orientation == Orientation.HORIZONTAL ? Orientation.VERTICAL : Orientation.HORIZONTAL;
    }

    /** First call: a login shell beside the session; later calls: switch between side by side and stacked. */
    private void toggleSplit() {
        if (second != null) {
            split.setOrientation(toggled(split.getOrientation()));
            split.setDividerPositions(0.5);
            return;
        }
        var shell = shellPath();
        second = createPane(new String[]{shell, "--login"}, "shell", UUID.randomUUID().toString(),
                () -> Platform.runLater(this::unsplit));
        setContent(null);
        split = new SplitPane(widget.getPane(), second.widget().getPane());
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.5);
        setContent(split);
        second.widget().start();
        secondFocused = true;
        focusActive();
    }

    /** The second shell ended (or the tab closes): back to the single widget. */
    private void unsplit() {
        var gone = second;
        if (gone == null) {
            return;
        }
        second = null;
        secondFocused = false;
        if (split != null) {
            split.getItems().clear();
            split = null;
            setContent(widget.getPane());
        }
        gone.widget().close();
        focus();
    }

    private void switchPane() {
        if (second != null) {
            secondFocused = !secondFocused;
            focusActive();
        }
    }

    private void focusActive() {
        var pane = secondFocused && second != null ? second : main;
        Platform.runLater(() -> pane.widget().getTerminalPanel().getCanvas().requestFocus());
    }

    private void forwardKeys(Pane pane, KeyEvent event) {
        if (event.getEventType() == KeyEvent.KEY_PRESSED && event.isControlDown() && event.isShiftDown()
                && !event.isAltDown() && !event.isMetaDown()) {
            var code = event.getCode();
            if (code == KeyCode.D) {
                toggleSplit();
                event.consume();
                return;
            }
            if (code == KeyCode.OPEN_BRACKET || code == KeyCode.CLOSE_BRACKET) {
                switchPane();
                event.consume();
                return;
            }
        }
        boolean plain = !event.isControlDown() && !event.isAltDown() && !event.isMetaDown();
        if (event.getEventType() == KeyEvent.KEY_PRESSED) {
            var sequence = sequenceFor(event.getCode(), event.isShiftDown(), event.isControlDown(),
                    event.isAltDown(), event.isMetaDown());
            if (sequence != null) {
                try {
                    pane.widget().getTtyConnector().write(sequence);
                } catch (IOException e) {
                    // the process is gone; the tab closes on its own
                }
                event.consume();
            }
        } else if (event.getEventType() == KeyEvent.KEY_TYPED && plain
                && ("\t".equals(event.getCharacter()) || (event.isShiftDown() && "\r".equals(event.getCharacter())))) {
            event.consume();
        }
    }

    /** Scrolls the scrollback; leaves the event to the widget when the app owns the mouse or the screen. */
    private void scrollBack(Pane pane, ScrollEvent event) {
        // JavaFX 27 on GTK sends an extra deltaY = 0 event with every wheel notch; the widget would report it to the
        // app as "wheel down", which cancels a notch up in Claude Code. A terminal has no horizontal scroll.
        if (event.getDeltaY() == 0) {
            event.consume();
            return;
        }
        var panel = pane.widget().getTerminalPanel();
        if (!panel.isLocalMouseAction(event) || pane.widget().getTerminalTextBuffer().isUsingAlternateBuffer()) {
            return;
        }
        int lines = wheel.lines(event.getDeltaY());
        for (int i = 0; i < Math.abs(lines); i++) {
            if (lines < 0) {
                panel.scrollUp();
            } else {
                panel.scrollDown();
            }
        }
        event.consume();
    }

    private void closeTab() {
        var tabPane = getTabPane();
        if (tabPane != null) {
            tabPane.getTabs().remove(this);
        }
        release();
    }

    private static com.pty4j.PtyProcess startProcess(String[] command, Path directory, Account account,
            String sessionId) {
        try {
            return new PtyProcessBuilder()
                    .setCommand(command)
                    .setEnvironment(childEnvironment(System.getenv(), ClaudeEvents.eventsDir(), sessionId, account))
                    .setDirectory(directory.toString())
                    .setInitialColumns(120)
                    .setInitialRows(36)
                    .setConsole(false)
                    .start();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start " + String.join(" ", command), e);
        }
    }

    /**
     * Environment for the child process. When FabricManager is started from inside a Claude Code session, the
     * session markers (CLAUDECODE, CLAUDE_PID, CLAUDE_CODE_SESSION_ID, CLAUDE_CODE_CHILD_SESSION, ...) would make
     * claude think it is nested: no trust dialog, transcript saving off. Settings env vars come back on their own,
     * because claude re-reads settings.json and the login shell re-reads the profile.
     */
    static Map<String, String> childEnvironment(Map<String, String> parent, Path eventsDir, String sessionId,
            Account account) {
        var env = childEnvironment(parent);
        if (account.isPersonal()) {
            env.remove("CLAUDE_CONFIG_DIR");
        } else {
            env.put("CLAUDE_CONFIG_DIR", account.configDir().toString());
        }
        env.put("FABRIC_EVENTS", eventsDir.toString());
        env.put("FABRIC_SESSION", sessionId);
        return env;
    }

    static Map<String, String> childEnvironment(Map<String, String> parent) {
        var env = new HashMap<String, String>();
        parent.forEach((key, value) -> {
            // TERMINAL_EMULATOR comes along when the app is started from an IntelliJ terminal; claude would then
            // apply its JetBrains workarounds to this terminal.
            if (!key.equals("CLAUDECODE") && !key.equals("CLAUDE_PID") && !key.startsWith("CLAUDE_CODE_")
                    && !key.equals("TERMINAL_EMULATOR")) {
                env.put(key, value);
            }
        });
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("TERM_PROGRAM", "fabric-manager");
        return env;
    }

    private static String shellPath() {
        var shell = System.getenv("SHELL");
        return shell == null || shell.isBlank() ? "/bin/bash" : shell;
    }

    /** Raw screen lines (trailing blanks included), top to bottom of the visible screen. */
    public java.util.List<String> screenLines() {
        var buffer = widget.getTerminalTextBuffer();
        buffer.lock();
        try {
            return java.util.List.of(buffer.getScreenLines().split("\\R", -1));
        } finally {
            buffer.unlock();
        }
    }
}
