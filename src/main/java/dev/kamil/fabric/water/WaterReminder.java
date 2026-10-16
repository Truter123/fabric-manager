package dev.kamil.fabric.water;

import dev.kamil.fabric.toast.Toasts;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;

/**
 * The in-app water reminder: a toast with Drank / Skip every EVERY_MIN minutes while ENABLED. The config is
 * re-read on every check, so saving it in the Water window applies at once. One water toast at a time; a toast
 * closed or replaced without an answer is logged as missed. Start and use from the FX thread.
 */
public final class WaterReminder {

    private static final int CHECK_SECONDS = 15;

    private final Toasts toasts;
    private final WaterStore store;
    private final WaterSchedule schedule = new WaterSchedule(Clock.systemDefaultZone());
    private final Timeline timer = new Timeline(new KeyFrame(Duration.seconds(CHECK_SECONDS), e -> check()));
    private Toasts.Handle current;

    public WaterReminder(Toasts toasts, WaterStore store) {
        this.toasts = toasts;
        this.store = store;
        timer.setCycleCount(Timeline.INDEFINITE);
    }

    public void start() {
        timer.play();
    }

    public void stop() {
        timer.stop();
    }

    private void check() {
        Thread.startVirtualThread(() -> {
            try {
                var config = store.readConfig();
                if (!schedule.due(config)) {
                    return;
                }
                int total = WaterStore.total(store.today(LocalDate.now()), LocalDate.now());
                Platform.runLater(() -> remind(config, total));
            } catch (IOException e) {
                System.err.println("Water reminder cannot read its files: " + e.getMessage());
            }
        });
    }

    private void remind(WaterStore.Config config, int total) {
        schedule.reminded();
        if (current != null) {
            current.close(); // logs the previous one as missed
        }
        int ml = config.ml();
        current = toasts.show("💧 Drink " + ml + " ml of water", "Today: " + total + " / " + config.goalMl() + " ml",
                List.of(new Toasts.Action("Drank " + ml + " ml", () -> log(ml, WaterStore.DRANK)),
                        new Toasts.Action("Skip", () -> log(ml, WaterStore.SKIPPED))),
                () -> log(ml, WaterStore.MISSED));
    }

    private void log(int ml, String status) {
        var date = LocalDate.now();
        var time = LocalTime.now();
        Thread.startVirtualThread(() -> {
            try {
                store.append(date, time, ml, status);
            } catch (IOException e) {
                System.err.println("Water reminder cannot write the log: " + e.getMessage());
            }
        });
    }
}
