package dev.kamil.fabric.water;

import dev.kamil.fabric.Dialogs;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Non-modal window over the water reminder: today's progress, Drank / Skip, settings and the reminder switch
 * (ENABLED in the config, obeyed at once by WaterReminder). It never notifies itself. Refreshes when shown and
 * every minute.
 */
public final class WaterWindow {

    private static WaterWindow instance;

    private final Stage window = new Stage();
    private final WaterStore store;
    private final Label today = new Label();
    private final ProgressBar progress = new ProgressBar(0);
    private final Label counts = new Label();
    private final Button drank = new Button();
    private final Button skip = new Button("Skip");
    private final Spinner<Integer> ml = new Spinner<>(1, 5000, 250, 50);
    private final Spinner<Integer> every = new Spinner<>(1, 1440, 60, 5);
    private final Spinner<Integer> goal = new Spinner<>(1, 20000, 2000, 250);
    private final ToggleButton reminder = new ToggleButton();
    private final Label status = new Label();
    private final VBox entries = new VBox(2);
    private final Timeline ticker = new Timeline(new KeyFrame(Duration.seconds(60), e -> refresh()));
    private int currentMl = 250;
    private final PauseTransition savedFade = new PauseTransition(Duration.seconds(2));
    private boolean updatingToggle;
    private boolean settingsShown;
    private WaterStore.Config lastConfig = WaterStore.Config.DEFAULT;

    /** Opens the window, or brings the existing one to front. */
    public static void open(Window owner) {
        open(owner, WaterStore.standardDir());
    }

    /** The open window, or null; for tests. */
    static Stage openStage() {
        return instance == null ? null : instance.window;
    }

    static void open(Window owner, java.nio.file.Path dir) {
        if (instance != null) {
            instance.window.show();
            instance.window.setIconified(false);
            instance.window.toFront();
            instance.refresh();
            return;
        }
        var created = new WaterWindow(owner, dir);
        instance = created;
        created.window.setOnHidden(e -> {
            created.ticker.stop();
            instance = null;
        });
        created.window.show();
        created.refresh();
        created.ticker.play();
    }

    private WaterWindow(Window owner, java.nio.file.Path dir) {
        store = new WaterStore(dir);
        window.initOwner(owner);
        window.setTitle("Water");
        ml.setEditable(true);
        every.setEditable(true);
        goal.setEditable(true);
        for (var spinner : List.of(ml, every, goal)) {
            spinner.setPrefWidth(110);
            spinner.focusedProperty().addListener((o, was, now) -> {
                if (!now) {
                    spinner.increment(0); // commit the typed text
                }
            });
        }
        ticker.setCycleCount(Timeline.INDEFINITE);
        savedFade.setOnFinished(e -> status.setText(""));
        progress.setMaxWidth(Double.MAX_VALUE);
        today.getStyleClass().add("history-meta");
        counts.getStyleClass().add("history-meta");
        status.getStyleClass().add("history-meta");

        drank.getStyleClass().add("primary");
        drank.setOnAction(e -> log(currentMl, WaterStore.DRANK));
        skip.setOnAction(e -> log(currentMl, WaterStore.SKIPPED));
        var save = new Button("Save");
        save.setOnAction(e -> saveConfig());
        reminder.setOnAction(e -> {
            if (!updatingToggle) {
                toggleReminder(reminder.isSelected());
            }
        });

        var buttons = new HBox(8, drank, skip);
        var settings = new HBox(8, new Label("ml per glass"), ml, new Label("every min"), every,
                new Label("goal ml"), goal, save);
        settings.setAlignment(Pos.CENTER_LEFT);
        var spacer = new VBox();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        var root = new VBox(10, today, progress, counts, buttons, settings, reminder, new Label("Today's log"),
                entries, spacer, status, Dialogs.keyHint("Esc close"));
        root.setPadding(new Insets(14));
        var scene = new Scene(root, 640, 560);
        scene.getStylesheets().add(WaterWindow.class.getResource("/app.css").toExternalForm());
        window.setScene(scene);
        Dialogs.closeOnEscape(scene, window);
        window.focusedProperty().addListener((o, was, now) -> {
            if (now) {
                refresh();
            }
        });
    }

    /** Reads the files and reads the files off the FX thread, then updates the view. */
    private void refresh() {
        refresh(false);
    }

    /** {@code afterSave}: also puts the saved settings back in the spinners and confirms with "Saved". */
    private void refresh(boolean afterSave) {
        Thread.startVirtualThread(() -> {
            try {
                var config = store.readConfig();
                var day = LocalDate.now();
                var log = store.today(day);
                Platform.runLater(() -> show(config, day, log, afterSave));
            } catch (IOException e) {
                Platform.runLater(() -> status.setText("Cannot read water files: " + e.getMessage()));
            }
        });
    }

    private void show(WaterStore.Config config, LocalDate day, List<WaterStore.Entry> log, boolean afterSave) {
        currentMl = config.ml();
        int total = WaterStore.total(log, day);
        today.setText("Today: " + total + " / " + config.goalMl() + " ml");
        progress.setProgress(Math.min(1.0, total / (double) config.goalMl()));
        counts.setText(WaterStore.count(log, day, WaterStore.DRANK) + " drank, "
                + WaterStore.count(log, day, WaterStore.SKIPPED) + " skipped, "
                + WaterStore.count(log, day, WaterStore.MISSED) + " missed");
        drank.setText("Drank " + config.ml() + " ml");
        // the spinners are only filled on open and after Save: a periodic refresh must not undo an edit in progress
        if (afterSave || !settingsShown) {
            ml.getValueFactory().setValue(config.ml());
            every.getValueFactory().setValue(config.everyMin());
            goal.getValueFactory().setValue(config.goalMl());
            settingsShown = true;
        }
        lastConfig = config;
        updatingToggle = true;
        reminder.setSelected(config.enabled());
        reminder.setText(config.enabled() ? "Reminder: on" : "Reminder: off");
        updatingToggle = false;
        var rows = new ArrayList<Label>();
        for (int i = log.size() - 1; i >= 0; i--) {
            var e = log.get(i);
            rows.add(new Label(e.time() + "   " + e.ml() + " ml   " + e.status()));
        }
        entries.getChildren().setAll(rows);
        if (afterSave) {
            status.setText("Saved");
            savedFade.playFromStart();
        } else if (savedFade.getStatus() != javafx.animation.Animation.Status.RUNNING) {
            status.setText("");
        }
    }

    private void log(int amount, String kind) {
        Thread.startVirtualThread(() -> {
            try {
                store.append(LocalDate.now(), LocalTime.now(), amount, kind);
            } catch (IOException e) {
                Platform.runLater(() -> status.setText("Cannot write the log: " + e.getMessage()));
                return;
            }
            refresh();
        });
    }

    private void saveConfig() {
        var config = new WaterStore.Config(ml.getValue(), every.getValue(), goal.getValue(),
                lastConfig.notification(), lastConfig.popup(), lastConfig.enabled());
        Thread.startVirtualThread(() -> {
            try {
                store.writeConfig(config);
            } catch (IOException e) {
                Platform.runLater(() -> status.setText("Cannot save the settings: " + e.getMessage()));
                return;
            }
            refresh(true);
        });
    }

    private void toggleReminder(boolean on) {
        Thread.startVirtualThread(() -> {
            try {
                var c = store.readConfig();
                store.writeConfig(new WaterStore.Config(c.ml(), c.everyMin(), c.goalMl(), c.notification(),
                        c.popup(), on));
            } catch (IOException e) {
                Platform.runLater(() -> status.setText("Cannot save the settings: " + e.getMessage()));
            }
            refresh();
        });
    }
}
