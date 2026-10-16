package dev.kamil.fabric.settings;

import dev.kamil.fabric.Dialogs;
import dev.kamil.fabric.bodydouble.VideoDownloader;
import dev.kamil.fabric.quotes.QuoteBar;
import dev.kamil.fabric.claude.ClaudeSettingsWindow;
import dev.kamil.fabric.claude.Notifier;
import dev.kamil.fabric.session.Account;
import dev.kamil.fabric.settings.SettingsSearch.Entry;
import dev.kamil.fabric.terminal.TerminalSettings;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;

/**
 * The one settings window: groups on the left, the fields of the selected group on the right. Changes apply and are
 * saved at once. Ctrl+Shift+, opens it; opening it again brings the same window to the front.
 */
public final class SettingsWindow {

    static final String GENERAL = "General";
    static final String TERMINAL = "Terminal";
    static final String NOTIFICATIONS = "Notifications";
    static final String ACCOUNTS = "Accounts";
    static final String BODY_DOUBLE = "Body double";
    static final String QUOTES = "Quotes";
    static final String CLAUDE = "Claude Code";

    public static final String SHOW_AGENTS = "general.showAgents";

    private static SettingsWindow current;

    /** One field: its search entry and the node showing label, control and help. */
    private record Row(Entry entry, Node node) {
    }

    private final AppSettings settings;
    private final Stage stage = new Stage();
    private final Path home;
    private final Path directory;
    private final Account account;
    private final List<String> groups = List.of(GENERAL, TERMINAL, NOTIFICATIONS, ACCOUNTS, BODY_DOUBLE, QUOTES, CLAUDE);
    private final List<Row> rows = new ArrayList<>();
    private final TextField search = new TextField();
    private final ListView<String> groupList = new ListView<>();
    private final VBox form = new VBox(14);
    private final Label saved = new Label("Saved");
    private final PauseTransition savedTimer = new PauseTransition(Duration.millis(1500));
    private Set<String> matching = Set.copyOf(groups);

    /** Opens the window, or brings the open one to the front, and focuses the search box. */
    public static void open(Window owner, AppSettings settings, Path directory, Account account) {
        if (current == null) {
            current = new SettingsWindow(owner, settings, directory, account);
            current.stage.setOnHidden(e -> current = null);
        }
        var window = current;
        window.stage.show();
        window.stage.setIconified(false);
        window.stage.toFront();
        window.stage.requestFocus();
        window.search.requestFocus();
        window.search.selectAll();
    }

    private SettingsWindow(Window owner, AppSettings settings, Path directory, Account account) {
        this.settings = settings;
        this.home = Path.of(System.getProperty("user.home"));
        this.directory = directory;
        this.account = account;
        stage.initOwner(owner);
        stage.setTitle("Settings");

        search.setPromptText("Search settings…");
        search.getStyleClass().add("settings-search");
        search.textProperty().addListener((o, was, now) -> applySearch());

        groupList.getItems().setAll(groups);
        groupList.setFixedCellSize(32);
        groupList.setPrefWidth(190);
        groupList.getStyleClass().add("settings-groups");
        groupList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                getStyleClass().removeAll("settings-dim", "settings-separated");
                if (!empty) {
                    if (!matching.contains(item)) {
                        getStyleClass().add("settings-dim");
                    }
                    if (CLAUDE.equals(item)) {
                        getStyleClass().add("settings-separated");
                        setText(item + "  ›");
                    }
                }
            }
        });
        groupList.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> showGroup(now));

        saved.getStyleClass().add("settings-saved");
        saved.setVisible(false);
        savedTimer.setOnFinished(e -> saved.setVisible(false));

        form.getStyleClass().add("settings-form");
        form.setPadding(new Insets(16));
        var scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("settings-scroll");

        var top = new HBox(8, search, saved);
        top.setPadding(new Insets(10));
        HBox.setHgrow(search, Priority.ALWAYS);
        top.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        top.getStyleClass().add("settings-top");
        var pane = new BorderPane(scroll);
        pane.setTop(top);
        pane.setLeft(groupList);

        buildRows();
        var scene = new Scene(pane, 860, 600);
        scene.getStylesheets().add(SettingsWindow.class.getResource("/app.css").toExternalForm());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                if (!search.getText().isEmpty()) {
                    search.clear();
                } else {
                    Dialogs.closeLater(stage);
                }
                e.consume();
            }
        });
        stage.setScene(scene);
        groupList.getSelectionModel().select(0);
    }

    // ---- search and group switching

    private void applySearch() {
        var query = search.getText();
        matching = SettingsSearch.groupsMatching(rows.stream().map(Row::entry).toList(), query);
        var selected = groupList.getSelectionModel().getSelectedItem();
        if (!query.isBlank() && !matching.contains(selected) && !matching.isEmpty()) {
            groupList.getSelectionModel().select(groups.stream().filter(matching::contains).findFirst().orElse(selected));
        } else {
            showGroup(selected);
        }
        groupList.refresh();
    }

    private void showGroup(String group) {
        form.getChildren().clear();
        if (group == null) {
            return;
        }
        var title = new Label(group);
        title.getStyleClass().add("settings-title");
        form.getChildren().add(title);
        var query = search.getText();
        for (var row : rows) {
            if (row.entry().group().equals(group)) {
                var visible = SettingsSearch.matches(row.entry(), query);
                row.node().setVisible(visible);
                row.node().setManaged(visible);
                form.getChildren().add(row.node());
            }
        }
        var reset = new Button("Reset group");
        reset.setOnAction(e -> resetGroup(group));
        var spacer = new Region();
        spacer.setMinHeight(8);
        form.getChildren().addAll(spacer, reset);
    }

    private void resetGroup(String group) {
        if (!Dialogs.confirm(stage, "Reset " + group + "?",
                "All " + group + " settings go back to their defaults.", "Reset")) {
            return;
        }
        Dialogs.attempt(stage, "Cannot reset the settings", () -> settings.removeAll(keysOf(group)));
        buildRows();
        showGroup(group);
        flash();
    }

    private List<String> keysOf(String group) {
        return switch (group) {
            case GENERAL -> List.of(SHOW_AGENTS);
            case TERMINAL -> List.of(TerminalSettings.FONT_FAMILY, TerminalSettings.FONT_SIZE_KEY,
                    TerminalSettings.SCROLLBACK, TerminalSettings.COPY_ON_SELECT, TerminalSettings.AUDIBLE_BELL);
            case NOTIFICATIONS -> List.of(Notifier.DESKTOP, Notifier.SOUND, Notifier.NEEDS_INPUT, Notifier.FINISHED);
            case ACCOUNTS -> {
                var keys = new ArrayList<String>();
                Account.available(home).forEach(a -> keys.add(Account.labelKey(a.name())));
                keys.add(Account.WORK_FOLDER_KEY);
                yield keys;
            }
            case BODY_DOUBLE -> List.of(VideoDownloader.CACHE_MB_KEY);
            case QUOTES -> List.of(QuoteBar.INTERVAL_KEY);
            default -> List.of();
        };
    }

    private void flash() {
        saved.setVisible(true);
        savedTimer.playFromStart();
    }

    // ---- the fields

    private void buildRows() {
        rows.clear();
        rows.add(bool(GENERAL, SHOW_AGENTS, true, "Show Agents panel on start",
                "Agents panel on the right when the app starts (Ctrl+Shift+A toggles it later)."));

        rows.add(fontRow());
        rows.add(intRow(TERMINAL, TerminalSettings.FONT_SIZE_KEY, 14, 6, 72, "Size",
                "Font size in points, 6 to 72. Applies to new tabs."));
        rows.add(intRow(TERMINAL, TerminalSettings.SCROLLBACK, TerminalSettings.DEFAULT_SCROLLBACK, 100, 1_000_000,
                "Scrollback", "Lines kept above the screen, 100 to 1000000. Applies to new tabs."));
        rows.add(bool(TERMINAL, TerminalSettings.COPY_ON_SELECT, false, "Copy on select",
                "Copy the selected text to the clipboard as soon as you select it. Applies to new tabs."));
        rows.add(bool(TERMINAL, TerminalSettings.AUDIBLE_BELL, false, "Audible bell",
                "Play a sound on the terminal bell. Applies to new tabs."));

        rows.add(bool(NOTIFICATIONS, Notifier.DESKTOP, true, "In-app notification",
                "Show a toast inside the window when a session needs you while you look elsewhere."));
        rows.add(bool(NOTIFICATIONS, Notifier.SOUND, true, "Sound",
                "Play a sound together with the notification."));
        rows.add(bool(NOTIFICATIONS, Notifier.NEEDS_INPUT, true, "Notify when Claude needs input",
                "A session waits for your answer, or hit an error."));
        rows.add(bool(NOTIFICATIONS, Notifier.FINISHED, true, "Notify when Claude finishes",
                "A session finished its work."));

        for (var a : Account.available(home)) {
            rows.add(accountLabelRow(a));
        }
        rows.add(workFolderRow());

        rows.add(cacheFolderRow());
        rows.add(intRow(BODY_DOUBLE, VideoDownloader.CACHE_MB_KEY, VideoDownloader.DEFAULT_CACHE_MB, 100, 1_000_000,
                "Cache size (MB)", "Oldest videos are deleted after a download when the cache is larger."));
        rows.add(clearCacheRow());

        rows.add(intRow(QUOTES, QuoteBar.INTERVAL_KEY, QuoteBar.DEFAULT_INTERVAL_MINUTES, 1, 1440,
                "Change quote every (minutes)", "How long one quote stays before the next one appears."));

        rows.add(claudeRow());
        applySearchQuietly();
    }

    private void applySearchQuietly() {
        matching = SettingsSearch.groupsMatching(rows.stream().map(Row::entry).toList(), search.getText());
    }

    private Row row(String group, String label, String help, Node control, boolean labelAbove) {
        var helpLabel = new Label(help);
        helpLabel.getStyleClass().add("settings-help");
        helpLabel.setWrapText(true);
        var box = new VBox(4);
        if (labelAbove) {
            var name = new Label(label);
            name.getStyleClass().add("settings-label");
            box.getChildren().add(name);
        }
        box.getChildren().addAll(control, helpLabel);
        box.getStyleClass().add("settings-row");
        return new Row(new Entry(group, label, help), box);
    }

    /** Applies one settings change; a failed write is shown, a saved one is flashed. */
    private void save(Runnable change) {
        if (Dialogs.attempt(stage, "Cannot save the setting", change)) {
            flash();
        }
    }

    private Row bool(String group, String key, boolean def, String label, String help) {
        var box = new CheckBox(label);
        box.setSelected(settings.getBoolean(key, def));
        box.selectedProperty().addListener((o, was, now) -> {
            save(() -> settings.setBoolean(key, now));
        });
        return row(group, label, help, box, false);
    }

    private Row intRow(String group, String key, int def, int min, int max, String label, String help) {
        var field = new TextField(Integer.toString(settings.getInt(key, def)));
        field.setMaxWidth(120);
        field.textProperty().addListener((o, was, now) -> {
            if (!now.matches("\\d{0,9}")) {
                field.setText(was);
                return;
            }
            var valid = !now.isEmpty() && Integer.parseInt(now) >= min && Integer.parseInt(now) <= max;
            field.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("invalid"), !valid);
            if (valid) {
                save(() -> settings.setInt(key, Integer.parseInt(now)));
            }
        });
        return row(group, label, help, field, true);
    }

    private Row fontRow() {
        var auto = "Auto";
        var box = new ComboBox<String>();
        box.getItems().add(auto);
        box.getItems().addAll(TerminalSettings.fontChoices());
        var chosen = settings.getString(TerminalSettings.FONT_FAMILY, "");
        box.setValue(chosen.isBlank() || !box.getItems().contains(chosen) ? auto : chosen);
        box.valueProperty().addListener((o, was, now) -> {
            save(() -> {
                if (now == null || auto.equals(now)) {
                    settings.remove(TerminalSettings.FONT_FAMILY);
                } else {
                    settings.setString(TerminalSettings.FONT_FAMILY, now);
                }
            });
        });
        return row(TERMINAL, "Font", "Monospace font family; Auto takes the first installed of JetBrains Mono, "
                + "Fira Code, DejaVu Sans Mono. Applies to new tabs.", box, true);
    }

    private Row accountLabelRow(Account a) {
        var field = new TextField(settings.getString(Account.labelKey(a.name()), ""));
        field.setPromptText(a.configDir().getFileName().toString());
        field.setMaxWidth(260);
        field.textProperty().addListener((o, was, now) -> {
            save(() -> {
                if (now.isBlank()) {
                    settings.remove(Account.labelKey(a.name()));
                } else {
                    settings.setString(Account.labelKey(a.name()), now.trim());
                }
            });
        });
        return row(ACCOUNTS, "Label for " + a.name(), "Name shown in the Agents panel and status line instead of "
                + "\"" + a.name() + "\"; empty shows the folder name " + a.configDir().getFileName() + ".", field, true);
    }

    private Row workFolderRow() {
        var field = new TextField(settings.getString(Account.WORK_FOLDER_KEY, Account.WORK_FOLDER));
        field.setEditable(false);
        HBox.setHgrow(field, Priority.ALWAYS);
        var choose = new Button("Choose…");
        choose.setOnAction(e -> {
            var chooser = new DirectoryChooser();
            chooser.setTitle("Work folder");
            var start = home.resolve(field.getText()).toFile();
            if (start.isDirectory()) {
                chooser.setInitialDirectory(start);
            }
            var picked = chooser.showDialog(stage);
            if (picked != null) {
                field.setText(picked.getAbsolutePath());
                save(() -> settings.setString(Account.WORK_FOLDER_KEY, picked.getAbsolutePath()));
            }
        });
        return row(ACCOUNTS, "Work folder", "New projects under this folder start with the work account. "
                + "Applies to projects opened from now on.", new HBox(8, field, choose), true);
    }

    private Row cacheFolderRow() {
        var dir = VideoDownloader.defaultCacheDir();
        var field = new TextField(dir.toString());
        field.setEditable(false);
        HBox.setHgrow(field, Priority.ALWAYS);
        var open = new Button("Open folder");
        open.setOnAction(e -> {
            try {
                new ProcessBuilder("xdg-open", dir.toString()).start();
            } catch (IOException ex) {
                Dialogs.error(stage, "Cannot open the folder", "xdg-open failed: " + ex.getMessage());
            }
        });
        return row(BODY_DOUBLE, "Cache folder", "Downloaded videos live here.", new HBox(8, field, open), true);
    }

    private Row clearCacheRow() {
        var clear = new Button("Clear cache");
        clear.setOnAction(e -> {
            if (Dialogs.confirm(stage, "Clear the video cache?",
                    "All downloaded videos are deleted; they download again when played.", "Clear")) {
                var freed = VideoDownloader.clearCache(VideoDownloader.defaultCacheDir());
                System.err.println("Video cache cleared: " + freed / (1024 * 1024) + " MB");
                flash();
            }
        });
        return row(BODY_DOUBLE, "Clear cache", "Deletes every downloaded video.", clear, true);
    }

    private Row claudeRow() {
        var accounts = new ComboBox<Account>();
        accounts.getItems().setAll(Account.available(home));
        accounts.setConverter(new StringConverter<>() {
            @Override
            public String toString(Account a) {
                return a == null ? "" : a.name() + "  (" + a.configDir().getFileName() + ")";
            }

            @Override
            public Account fromString(String s) {
                return null;
            }
        });
        accounts.getSelectionModel().select(accounts.getItems().stream()
                .filter(a -> a.name().equals(account.name())).findFirst().orElse(null));
        var open = new Button("Open Claude config browser");
        open.setOnAction(e -> {
            var chosen = accounts.getValue() == null ? account : accounts.getValue();
            ClaudeSettingsWindow.open(stage, directory, chosen);
        });
        var info = new Label("Browse and edit the Claude Code configuration (settings, CLAUDE.md, agents, skills, "
                + "commands, MCP servers) of the current project.");
        info.setWrapText(true);
        info.getStyleClass().add("settings-help");
        var control = new VBox(8, info, accounts, open);
        return row(CLAUDE, "Claude config browser", "Which account's ~/.claude folder to browse: pick it, "
                + "then open the browser for the current project.", control, true);
    }
}
