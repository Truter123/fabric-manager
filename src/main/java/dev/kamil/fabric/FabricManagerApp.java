package dev.kamil.fabric;

import dev.kamil.fabric.bodydouble.BodyDoubleWindow;
import dev.kamil.fabric.english.EnglishWindow;
import dev.kamil.fabric.notes.NotesWindow;
import dev.kamil.fabric.quotes.QuoteBar;
import dev.kamil.fabric.processes.ProcessesWindow;
import dev.kamil.fabric.tasks.TasksWindow;
import dev.kamil.fabric.git.ChangesWindow;
import dev.kamil.fabric.git.GitBranch;
import dev.kamil.fabric.search.TranscriptSearchWindow;
import dev.kamil.fabric.session.SavedSession;
import dev.kamil.fabric.git.GitRepo;
import dev.kamil.fabric.session.Account;
import dev.kamil.fabric.session.AgentsPanel;
import dev.kamil.fabric.session.OverviewGrid;
import dev.kamil.fabric.session.HistoryWindow;
import dev.kamil.fabric.session.ProjectPicker;
import dev.kamil.fabric.claude.ClaudeEvents;
import dev.kamil.fabric.claude.Notifier;
import dev.kamil.fabric.claude.usage.SessionTranscripts;
import dev.kamil.fabric.claude.usage.TranscriptMarkdown;
import dev.kamil.fabric.claude.usage.TranscriptUsage;
import dev.kamil.fabric.claude.usage.QuotaClient;
import dev.kamil.fabric.claude.usage.UsageView;
import dev.kamil.fabric.snippets.SnippetPicker;
import dev.kamil.fabric.snippets.SnippetStore;
import dev.kamil.fabric.ssh.SshConfig;
import dev.kamil.fabric.ssh.SshHostStore;
import dev.kamil.fabric.ssh.SshPicker;
import dev.kamil.fabric.session.Database;
import dev.kamil.fabric.settings.AppSettings;
import dev.kamil.fabric.settings.SettingsWindow;
import dev.kamil.fabric.session.RecentProjects;
import dev.kamil.fabric.session.SessionSidebar;
import dev.kamil.fabric.session.SessionStore;
import dev.kamil.fabric.session.Tool;
import dev.kamil.fabric.terminal.TerminalTab;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.Image;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/**
 * FabricManager: tabbed terminal for Claude Code and other AI CLIs.
 *
 * <p>Ctrl+Shift+N: project picker, Ctrl+Shift+T: new shell tab in the current directory,
 * Ctrl+Shift+W: close tab, Ctrl+Shift+A: toggle the agents panel,
 * Ctrl+Shift+H: git history of the current tab's repository,
 * Ctrl+Tab / Ctrl+Shift+Tab: next / previous tab. Shift+Tab and Tab go to the tool,
 * Shift+Enter: new line in the prompt.
 */
public final class FabricManagerApp extends Application {

    private final TabPane tabs = new TabPane();
    private final Database db = Database.standard().migrate();
    private QuoteBar quoteBar;
    private final AppSettings settings = AppSettings.use(new AppSettings(db));
    private final RecentProjects recent = new RecentProjects(db,
            Path.of(System.getProperty("user.home")).resolve(".config/fabric-manager/recent")).load();
    private final SessionStore store = new SessionStore(db);
    private final TerminalTab.Lifecycle lifecycle = new TerminalTab.Lifecycle() {
        @Override
        public void started(String sessionId, Path directory, Tool tool, Account account, String title) {
            Dialogs.logFailure(() -> store.started(sessionId, directory, tool, account, title));
        }

        @Override
        public void renamed(String sessionId, String title) {
            Dialogs.logFailure(() -> store.renamed(sessionId, title));
        }

        @Override
        public void activityChanged(String sessionId) {
            sidebar.refresh();
            refreshViews();
        }

        @Override
        public void ended(String sessionId) {
            Dialogs.logFailure(() -> store.ended(sessionId));
        }
    };
    private final BorderPane root = new BorderPane(tabs);
    private final dev.kamil.fabric.toast.Toasts toasts = new dev.kamil.fabric.toast.Toasts();
    private final Notifier notifier = Notifier.system(
            (title, text) -> Platform.runLater(() -> toasts.show(title, text)));
    private dev.kamil.fabric.water.WaterReminder waterReminder;
    private SessionSidebar sidebar;
    private AgentsPanel agentsPanel;
    private OverviewGrid overview;
    private final javafx.scene.control.Label statusText = new javafx.scene.control.Label();
    private final java.util.Map<String, String> contextLabels = new java.util.concurrent.ConcurrentHashMap<>();
    private Stage stage;
    private ClaudeEvents.Watcher watcher;
    private final SnippetStore snippets = new SnippetStore(db);
    private final SshHostStore sshHosts = new SshHostStore(db);
    private final QuotaClient quotaClient = new QuotaClient();
    private final UsageView usageView = new UsageView();
    private final java.util.Map<String, TranscriptUsage> transcripts = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService usageTimer =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                var thread = new Thread(r, "usage-refresh");
                thread.setDaemon(true);
                return thread;
            });
    private Path lastDirectory = Path.of(System.getProperty("user.home"));

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Dialogs.logFailure(snippets::seedIfEmpty);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        // No tabs: the window stays open as the main menu (sidebar buttons and shortcuts).
        var empty = new javafx.scene.control.Label("""
            Ctrl+Shift+N   new session (pick a project)
            Ctrl+Shift+T   shell
            Ctrl+Shift+S   snippets
            Ctrl+Shift+J   notes
            Ctrl+Shift+K   tasks
            Ctrl+Shift+P   processes
            Ctrl+Shift+V   body double video
            Ctrl+Shift+Q   quotes
            F1             all keys""");
        empty.getStyleClass().add("empty-hint");
        empty.visibleProperty().bind(javafx.beans.binding.Bindings.isEmpty(tabs.getTabs()));
        empty.setMouseTransparent(true);
        var statusBar = new javafx.scene.layout.HBox(statusText);
        statusBar.getStyleClass().add("status-line");
        statusBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        statusBar.setMinHeight(22);
        statusBar.setPrefHeight(22);
        statusBar.setMaxHeight(22);
        overview = new OverviewGrid(tabs, contextLabels::get, () -> {
            if (tabs.getSelectionModel().getSelectedItem() instanceof TerminalTab tab) {
                tab.focus();
            }
        });
        quoteBar = new QuoteBar(db);
        javafx.scene.layout.StackPane.setAlignment(toasts, javafx.geometry.Pos.TOP_RIGHT);
        root.setCenter(new BorderPane(new javafx.scene.layout.StackPane(tabs, empty, overview, toasts), quoteBar,
                null, statusBar, null));
        sidebar = new SessionSidebar(tabs);
        sidebar.setOnHistory(tab -> openHistory(tab.directory()));
        sidebar.setOnFuse(this::fuse);
        sidebar.setOnDuplicate(this::duplicate);
        sidebar.setOnPin((tab, pinned) ->
                Dialogs.attempt(stage, "Cannot pin the session", () -> store.setPinned(tab.sessionId(), pinned)));
        sidebar.setOnNewInProject(dir -> open(Tool.defaultTool(), dir, currentAccount()));
        sidebar.setUsageView(usageView, usageView.summaryProperty());
        root.setLeft(sidebar);
        agentsPanel = new AgentsPanel(tabs, contextLabels::get);
        root.setRight(settings.getBoolean(SettingsWindow.SHOW_AGENTS, true) ? agentsPanel : null);
        settings.addListener(key -> {
            if (key.startsWith("account.")) {
                javafx.application.Platform.runLater(this::refreshViews);
            }
        });
        tabs.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) c -> {
            while (c.next()) {
                for (var removed : c.getRemoved()) {
                    if (removed instanceof TerminalTab t && t.sessionId() != null) {
                        contextLabels.remove(t.sessionId());
                        transcripts.remove(t.sessionId());
                        sidebar.forget(t.sessionId());
                    }
                }
            }
            refreshViews();
        });
        tabs.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> refreshViews());
        var statusTimer = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.seconds(3), e -> {
                    refreshViews();
                    usageView.refreshAge(java.time.Instant.now());
                }));
        statusTimer.setCycleCount(javafx.animation.Animation.INDEFINITE);
        statusTimer.play();
        stage.iconifiedProperty().addListener((o, was, iconified) -> {
            if (iconified) {
                statusTimer.pause();
            } else {
                refreshViews();
                statusTimer.play();
            }
        });
        // Quota: once at start for the collapsed line, then only while the usage panel is expanded.
        usageTimer.execute(this::refreshUsage);
        sidebar.usageExpandedProperty().addListener((o, was, expanded) -> setUsagePolling(expanded));
        if (sidebar.usageExpandedProperty().get()) {
            setUsagePolling(true);
        }
        // A click on a tab header or a sidebar row takes the keyboard focus; give it back to the terminal. A click
        // outside the open overview closes it first, or Esc would reach the terminal behind it.
        root.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (overview.isOpen() && !(e.getTarget() instanceof javafx.scene.Node n && inside(n, overview))) {
                overview.close();
            }
            if (tabs.getSelectionModel().getSelectedItem() instanceof TerminalTab tab) {
                tab.focus();
            }
        });

        var scene = new Scene(root, 1200, 800);
        scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
        bindKeys(scene);

        stage.setTitle("FabricManager");
        for (int size : new int[] {16, 24, 32, 48, 64, 128, 256}) {
            stage.getIcons().add(new Image(getClass().getResourceAsStream("/icons/app-" + size + ".png")));
        }
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            if (confirmQuit()) {
                Platform.exit();
            } else {
                e.consume();
            }
        });
        stage.show();
        waterReminder = new dev.kamil.fabric.water.WaterReminder(toasts,
                new dev.kamil.fabric.water.WaterStore(dev.kamil.fabric.water.WaterStore.standardDir()));
        waterReminder.start();
        stage.focusedProperty().addListener((o, was, focused) -> {
            if (focused && tabs.getSelectionModel().getSelectedItem() instanceof TerminalTab tab) {
                tab.setUnseen(false);
            }
        });
        tabs.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (stage.isFocused() && now instanceof TerminalTab tab) {
                tab.setUnseen(false);
            }
        });
        try {
            ClaudeEvents.installPlugin(ClaudeEvents.pluginDir());
            watcher = ClaudeEvents.Watcher.start(ClaudeEvents.eventsDir(), this::onEvents);
        } catch (UncheckedIOException e) {
            System.err.println("Session notifications are off: " + e.getMessage());
        }

        var args = getParameters().getRaw();
        var arg = args.isEmpty() ? null : Path.of(args.get(0)).toAbsolutePath();
        if (arg != null && Files.isDirectory(arg)) {
            var tool = recent.lastTool().orElseGet(Tool::defaultTool);
            var account = recent.accountFor(arg);
            Dialogs.logFailure(() -> recent.remember(arg, tool, account));
            open(tool, arg, account);
        }
    }

    @Override
    public void stop() {
        if (sidebar != null) {
            sidebar.stop();
        }
        if (waterReminder != null) {
            waterReminder.stop();
        }
        usageTimer.shutdownNow();
        if (watcher != null) {
            watcher.close();
        }
        tabs.getTabs().clear();
        // pty reader threads are not daemons
        System.exit(0);
    }

    private void bindKeys(Scene scene) {
        var accelerators = scene.getAccelerators();
        // Each sidebar button and its shortcut run the same action.
        var actions = new java.util.ArrayList<SessionSidebar.Action>();
        // Snippets, Worktree and Changes act on the selected session: disabled while no terminal tab is selected
        var hasSession = javafx.beans.binding.Bindings.createBooleanBinding(
                () -> tabs.getSelectionModel().getSelectedItem() instanceof TerminalTab,
                tabs.getSelectionModel().selectedItemProperty());
        java.util.function.BiConsumer<Shortcut, SessionSidebar.Action> add = (shortcut, action) -> {
            var run = action.run();
            accelerators.put(shortcut.combo(), () -> Platform.runLater(run));
            actions.add(action);
        };
        add.accept(Shortcut.NEW, new SessionSidebar.Action(SessionSidebar.Group.PRIMARY, "+", "New", Shortcut.NEW.tooltip(), this::pick));
        add.accept(Shortcut.SHELL, new SessionSidebar.Action(SessionSidebar.Group.PRIMARY, ">_", "Shell", Shortcut.SHELL.tooltip(),
                () -> open(Tool.SHELL, currentDirectory(), currentAccount())));
        add.accept(Shortcut.SETTINGS, new SessionSidebar.Action(SessionSidebar.Group.FOOTER, "⚙", "Settings", Shortcut.SETTINGS.tooltip(),
                () -> openClaudeSettings(currentDirectory(), currentAccount())));
        add.accept(Shortcut.NOTES, new SessionSidebar.Action(SessionSidebar.Group.GRID, "✎", "Notes", Shortcut.NOTES.tooltip(),
                () -> NotesWindow.open(stage, db)));
        add.accept(Shortcut.TASKS, new SessionSidebar.Action(SessionSidebar.Group.GRID, "☐", "Tasks", Shortcut.TASKS.tooltip(),
                () -> TasksWindow.open(stage, db)));
        add.accept(Shortcut.SNIPPETS, new SessionSidebar.Action(SessionSidebar.Group.GRID, "⌘", "Snippets", Shortcut.SNIPPETS.tooltip(),
                this::openSnippets, hasSession));
        add.accept(Shortcut.HISTORY, new SessionSidebar.Action(SessionSidebar.Group.GRID, "⎇", "History", Shortcut.HISTORY.tooltip(),
                () -> openHistory(currentDirectory())));
        add.accept(Shortcut.PROCESSES, new SessionSidebar.Action(SessionSidebar.Group.GRID, "▤", "Processes", Shortcut.PROCESSES.tooltip(),
                () -> ProcessesWindow.open(stage, (command, directory) -> open(
                        command == null ? Tool.SHELL : new Tool("command", command), directory, currentAccount()))));
        add.accept(Shortcut.VIDEO, new SessionSidebar.Action(SessionSidebar.Group.GRID, "▶", "Video", Shortcut.VIDEO.tooltip(),
                () -> BodyDoubleWindow.toggle(stage, db)));
        add.accept(Shortcut.QUOTES, new SessionSidebar.Action(SessionSidebar.Group.GRID, "❝", "Quotes", Shortcut.QUOTES.tooltip(),
                () -> dev.kamil.fabric.quotes.QuotesWindow.open(stage, db, quoteBar::refresh)));
        add.accept(Shortcut.ENGLISH, new SessionSidebar.Action(SessionSidebar.Group.GRID, "A", "English", Shortcut.ENGLISH.tooltip(),
                () -> EnglishWindow.open(stage)));
        add.accept(Shortcut.AGENTS, new SessionSidebar.Action(SessionSidebar.Group.GRID, "◧", "Agents", Shortcut.AGENTS.tooltip(),
                () -> root.setRight(root.getRight() == null ? agentsPanel : null)));
        add.accept(Shortcut.OVERVIEW, new SessionSidebar.Action(SessionSidebar.Group.GRID, "▦", "Overview", Shortcut.OVERVIEW.tooltip(),
                () -> overview.toggle()));
        add.accept(Shortcut.SSH, new SessionSidebar.Action(SessionSidebar.Group.GRID, "⇄", "SSH", Shortcut.SSH.tooltip(),
                this::openSsh));
        add.accept(Shortcut.WORKTREE, new SessionSidebar.Action(SessionSidebar.Group.GRID, "⑂", "Worktree", Shortcut.WORKTREE.tooltip(),
                this::openWorktree, hasSession));
        add.accept(Shortcut.CHANGES, new SessionSidebar.Action(SessionSidebar.Group.GRID, "±", "Changes", Shortcut.CHANGES.tooltip(),
                this::openChanges, hasSession));
        add.accept(Shortcut.WATER, new SessionSidebar.Action(SessionSidebar.Group.GRID, "💧", "Water", Shortcut.WATER.tooltip(),
                () -> dev.kamil.fabric.water.WaterWindow.open(stage)));
        add.accept(Shortcut.SERVERS, new SessionSidebar.Action(SessionSidebar.Group.GRID, "▤", "Servers", Shortcut.SERVERS.tooltip(),
                () -> dev.kamil.fabric.servers.ServersWindow.open(stage)));
        add.accept(Shortcut.SEARCH, new SessionSidebar.Action(SessionSidebar.Group.GRID, "⌕", "Search", Shortcut.SEARCH.tooltip(),
                this::openTranscriptSearch));
        sidebar.setActions(actions);
        // Shortcut only: the session list already does these with a click, double-click or right-click.
        accelerators.put(Shortcut.NEXT_TAB.combo(),
                () -> tabs.getSelectionModel().selectNext());
        accelerators.put(Shortcut.RENAME.combo(), () -> {
            if (tabs.getSelectionModel().getSelectedItem() instanceof TerminalTab tab) {
                sidebar.rename(tab);
            }
        });
        accelerators.put(Shortcut.DUPLICATE.combo(), () -> {
            if (tabs.getSelectionModel().getSelectedItem() instanceof TerminalTab tab) {
                duplicate(tab);
            }
        });
        accelerators.put(Shortcut.CLOSE.combo(), () -> {
            if (tabs.getSelectionModel().getSelectedItem() instanceof TerminalTab tab) {
                Platform.runLater(tab::requestClose);
            }
        });
        accelerators.put(Shortcut.PREVIOUS_TAB.combo(), () -> tabs.getSelectionModel().selectPrevious());
        // A filter, not an accelerator: a focused terminal would otherwise take F1 for itself.
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (Shortcut.KEYS.combo().match(e)) {
                KeySheet.toggle(stage);
                e.consume();
            }
        });
    }

    private void openSnippets() {
        if (tabs.getSelectionModel().getSelectedItem() instanceof TerminalTab tab) {
            new SnippetPicker(stage, snippets, tab::send, () -> tab.directory().toString()).show();
        }
    }

    private void openChanges() {
        if (tabs.getSelectionModel().getSelectedItem() instanceof TerminalTab tab) {
            ChangesWindow.open(stage, tab.directory());
        }
    }

    /** Searches every account's transcripts; the chosen hit reopens that claude conversation. */
    private void openTranscriptSearch() {
        var accounts = Account.available(Path.of(System.getProperty("user.home")));
        var homes = accounts.stream().map(Account::configDir).toList();
        new TranscriptSearchWindow(stage, homes, hit -> {
            if (hit.project() == null) {
                Dialogs.error(stage, "Cannot resume", "The transcript does not say which folder it ran in.");
                return;
            }
            var account = accounts.stream().filter(a -> a.configDir().equals(hit.claudeHome())).findFirst()
                    .orElse(currentAccount());
            var title = hit.project().getFileName() == null ? "/" : hit.project().getFileName().toString();
            var saved = new SavedSession(hit.sessionId(), hit.project(), new Tool("claude", "claude"), title,
                    hit.time(), hit.time(), null, account);
            show(() -> TerminalTab.resume(saved, lifecycle), saved.tool(), saved.project());
        }).show();
    }

    private void openWorktree() {
        if (!(tabs.getSelectionModel().getSelectedItem() instanceof TerminalTab tab)) {
            return;
        }
        var directory = tab.directory();
        Thread.ofPlatform().daemon().name("worktree-git").start(() -> {
            var found = GitRepo.of(directory);
            Platform.runLater(() -> {
                if (found.isEmpty()) {
                    Dialogs.error(stage, "No git repository", directory + " is not inside a git repository.");
                } else {
                    askWorktree(tab, found.get());
                }
            });
        });
    }

    private void askWorktree(TerminalTab tab, GitRepo found) {
        var dialog = new javafx.scene.control.TextInputDialog();
        dialog.setTitle("FabricManager");
        dialog.setHeaderText("Branch for the new worktree (created when it does not exist)");
        dialog.setGraphic(null);
        Dialogs.style(dialog.getDialogPane());
        dialog.initOwner(stage);
        dialog.showAndWait().map(String::strip).filter(name -> !name.isEmpty()).ifPresent(branch -> {
            var repo = found;
            Dialogs.attempt(stage, "Worktree failed", () -> {
                Path path;
                try {
                    path = repo.addWorktree(branch);
                } catch (GitRepo.GitException e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
                open(tab.tool(), path, tab.account());
            });
        });
    }

    private void openSsh() {
        new SshPicker(stage, SshConfig.standard(), sshHosts, entry -> open(
                entry.fromApp()
                        ? Tool.ssh(entry.app().name(), entry.app().hostName(), entry.app().user(),
                                entry.app().port(), entry.app().identity())
                        : Tool.ssh(entry.alias()),
                Path.of(System.getProperty("user.home")), currentAccount())).show();
    }

    private java.util.concurrent.ScheduledFuture<?> usagePolling;

    /** Fetches now and every 120 s while on; cancels when off. FX thread only. */
    private void setUsagePolling(boolean on) {
        if (usagePolling != null) {
            usagePolling.cancel(false);
            usagePolling = null;
        }
        if (on) {
            usagePolling = usageTimer.scheduleWithFixedDelay(
                    this::refreshUsage, 0, 120, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private void refreshUsage() {
        try {
            var quota = quotaClient.fetch();
            Platform.runLater(() -> usageView.update(quota, java.time.ZoneId.systemDefault(), java.time.Instant.now()));
        } catch (RuntimeException e) {
            Diagnostics.warnOnce("Usage refresh failed: " + e);
        }
    }

    /** Context usage of one session, read off the FX thread. */
    private void refreshContext(String sessionId, Path directory) {
        usageTimer.execute(() -> {
            try {
                SessionTranscripts.find(ClaudeEvents.eventsDir(), sessionId, directory).ifPresent(path -> {
                    var usage = transcripts.computeIfAbsent(sessionId, id -> new TranscriptUsage(path));
                    try {
                        usage.refresh();
                    } catch (java.io.IOException e) {
                        Diagnostics.warnOnce("Cannot read the transcript " + path + ": " + e.getMessage());
                        return;
                    }
                    var text = UsageView.contextLabel(usage.contextTokens(), usage.window());
                    contextLabels.put(sessionId, text);
                    Platform.runLater(() -> {
                        sidebar.setContext(sessionId, text);
                        refreshViews();
                    });
                });
            } catch (RuntimeException e) {
                Diagnostics.warnOnce("Context refresh failed: " + e);
            }
        });
    }

    /** Applies the last state change of the batch. */
    private void onEvents(String sessionId, java.util.List<ClaudeEvents.Event> events) {
        for (var t : tabs.getTabs()) {
            if (t instanceof TerminalTab tab && tab.sessionId().equals(sessionId)) {
                refreshContext(sessionId, tab.directory());
                break;
            }
        }
        TerminalTab.Activity last = null;
        for (var event : events) {
            if (event instanceof ClaudeEvents.ActivityChanged changed) {
                last = changed.activity();
            }
        }
        if (last != null) {
            var status = last.name();
            Dialogs.logFailure(() -> store.status(sessionId, status));
            onActivity(sessionId, last);
        }
    }

    private void onActivity(String sessionId, TerminalTab.Activity activity) {
        for (var t : tabs.getTabs()) {
            if (t instanceof TerminalTab tab && tab.sessionId().equals(sessionId)) {
                tab.setActivity(activity);
                sidebar.refresh();
                refreshViews();
                if (activity == TerminalTab.Activity.WORKING) {
                    return;
                }
                if (!(tab.isSelected() && stage.isFocused())) {
                    tab.setUnseen(true);
                    sidebar.refresh();
                    refreshViews();
                }
                notifier.onState(sessionId, tab.name().isBlank() ? tab.directory().getFileName() == null ? "/"
                        : tab.directory().getFileName().toString() : tab.name(), activity, stage.isFocused(),
                        tab.isSelected());
                return;
            }
        }
    }

    /** Redraws the agents panel and the status line (FX thread). */
    private void refreshViews() {
        agentsPanel.refresh();
        statusText.setText(tabs.getSelectionModel().getSelectedItem() instanceof TerminalTab tab
                ? AgentsPanel.statusLine(tab, contextLabels.get(tab.sessionId())) : "");
    }

    private Path currentDirectory() {
        return tabs.getSelectionModel().getSelectedItem() instanceof TerminalTab tab
                ? tab.directory() : lastDirectory;
    }

    private Account currentAccount() {
        return tabs.getSelectionModel().getSelectedItem() instanceof TerminalTab tab
                ? tab.account() : Account.personal(Path.of(System.getProperty("user.home")));
    }

    private void openHistory(Path directory) {
        Thread.ofPlatform().daemon().name("history-git").start(() -> {
            var found = GitRepo.of(directory);
            var branch = found.isPresent() ? GitBranch.of(directory).orElse("detached") : "";
            Platform.runLater(() -> found.ifPresentOrElse(
                    repo -> HistoryWindow.open(stage, repo, branch,
                            dir -> open(Tool.defaultTool(), dir, currentAccount())),
                    () -> Dialogs.error(stage, "No git repository", directory + " is not inside a git repository.")));
        });
    }

    private void openClaudeSettings(Path directory, Account account) {
        SettingsWindow.open(stage, settings, directory, account);
    }

    private void pick() {
        var openIds = new java.util.HashSet<String>();
        for (var t : tabs.getTabs()) {
            if (t instanceof TerminalTab tab) {
                openIds.add(tab.sessionId());
            }
        }
        new ProjectPicker(stage, recent, store, openIds).showAndWait().ifPresent(choice -> {
            if (choice.resume() != null) {
                var saved = choice.resume();
                Dialogs.attempt(stage, "Cannot save the recent project",
                        () -> recent.remember(saved.project(), saved.tool(), saved.account()));
                show(() -> TerminalTab.resume(saved, lifecycle), saved.tool(), saved.project());
                return;
            }
            Dialogs.attempt(stage, "Cannot save the recent project",
                    () -> recent.remember(choice.directory(), choice.tool(), choice.account()));
            open(choice.tool(), choice.directory(), choice.account());
        });
    }

    private void open(Tool tool, Path directory, Account account) {
        show(() -> TerminalTab.open(tool, directory, account, lifecycle), tool, directory);
    }

    /** Exports the transcript of {@code a} to markdown and asks {@code b} to read it. */
    private void fuse(TerminalTab a, TerminalTab b) {
        Dialogs.attempt(stage, "Fuse failed", () -> {
            var aTitle = a.displayName();
            var transcript = SessionTranscripts.find(ClaudeEvents.eventsDir(), a.sessionId(), a.directory());
            if (transcript.isEmpty()) {
                Dialogs.error(stage, "No transcript", "Session \"" + aTitle + "\" has no transcript yet.");
                return;
            }
            try {
                var md = TranscriptMarkdown.export(transcript.get(), TranscriptMarkdown.defaultDir(), aTitle,
                        a.directory());
                // No '@' in the prompt: the file autocomplete popup would swallow Enter.
                b.send("Read the file " + md + " - it is the transcript of another session (\"" + aTitle
                        + "\"). Continue with that context too.\n");
                tabs.getSelectionModel().select(b);
                if (Dialogs.confirm(stage, "Close \"" + aTitle + "\"?",
                        "\"" + b.displayName() + "\" now has its context.", "Close")) {
                    a.requestClose();
                }
            } catch (java.io.IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** Opens a new tab with the tool, folder and account of {@code tab}. */
    private void duplicate(TerminalTab tab) {
        show(tab::duplicate, tab.tool(), tab.directory());
    }

    private void show(java.util.function.Supplier<TerminalTab> create, Tool tool, Path directory) {
        TerminalTab tab;
        try {
            tab = create.get();
        } catch (UncheckedIOException e) {
            Dialogs.error(stage, "Cannot start " + tool.name(), e.getMessage() + "\n" + e.getCause().getMessage());
            return;
        }
        lastDirectory = directory;
        tab.setPinned(store.isPinned(tab.sessionId()));
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
        tab.focus();
    }

    /** Asks before quitting while a tool still runs in any tab. */
    private boolean confirmQuit() {
        long running = tabs.getTabs().stream()
                .filter(t -> t instanceof TerminalTab tab && tab.busy())
                .count();
        if (running == 0) {
            return true;
        }
        var sessions = running == 1 ? "1 session is" : running + " sessions are";
        return Dialogs.confirm(stage, "Quit FabricManager?", sessions + " still running. Quitting ends them.", "Quit");
    }

    private static boolean inside(javafx.scene.Node node, javafx.scene.Node ancestor) {
        for (var n = node; n != null; n = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }
}
