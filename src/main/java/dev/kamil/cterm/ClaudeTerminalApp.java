package dev.kamil.cterm;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

/**
 * Tabbed terminal for Claude Code.
 *
 * <p>Ctrl+Shift+N: new Claude tab (pick a directory), Ctrl+Shift+T: new shell tab,
 * Ctrl+Shift+W: close tab, Ctrl+Tab / Ctrl+Shift+Tab: next / previous tab, Shift+Enter: new line in the Claude prompt.
 */
public final class ClaudeTerminalApp extends Application {

    private final TabPane tabs = new TabPane();
    private Path lastDirectory;

    @Override
    public void start(Stage stage) {
        lastDirectory = startDirectory();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabs.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) change -> {
            if (tabs.getTabs().isEmpty()) {
                Platform.exit();
            }
        });

        var scene = new Scene(tabs, 1200, 800);
        scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
        bindKeys(scene, stage);

        stage.setTitle("Claude Terminal FX");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> Platform.exit());
        stage.show();
        open(TerminalTab.claude(lastDirectory));
    }

    @Override
    public void stop() {
        tabs.getTabs().clear();
        // pty reader threads are not daemons
        System.exit(0);
    }

    private void bindKeys(Scene scene, Stage stage) {
        var accelerators = scene.getAccelerators();
        accelerators.put(combo(KeyCode.N), () -> chooseDirectory(stage));
        accelerators.put(combo(KeyCode.T), () -> open(TerminalTab.shell(lastDirectory)));
        accelerators.put(combo(KeyCode.W), () -> {
            var tab = tabs.getSelectionModel().getSelectedItem();
            if (tab != null) {
                tabs.getTabs().remove(tab);
                tab.getOnClosed().handle(null);
            }
        });
        accelerators.put(new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN),
                () -> tabs.getSelectionModel().selectNext());
        accelerators.put(combo(KeyCode.TAB), () -> tabs.getSelectionModel().selectPrevious());
    }

    private void chooseDirectory(Stage stage) {
        var chooser = new DirectoryChooser();
        chooser.setTitle("Start Claude in…");
        chooser.setInitialDirectory(lastDirectory.toFile());
        File dir = chooser.showDialog(stage);
        if (dir != null) {
            lastDirectory = dir.toPath();
            open(TerminalTab.claude(lastDirectory));
        }
    }

    private void open(TerminalTab tab) {
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
        tab.focus();
    }

    private Path startDirectory() {
        var args = getParameters().getRaw();
        var dir = Path.of(args.isEmpty() ? System.getProperty("user.dir") : args.get(0)).toAbsolutePath();
        return Files.isDirectory(dir) ? dir : Path.of(System.getProperty("user.home"));
    }

    private static KeyCodeCombination combo(KeyCode code) {
        return new KeyCodeCombination(code, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN);
    }
}
