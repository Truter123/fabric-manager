package dev.kamil.fabric.water;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * When the next water reminder is due: every EVERY_MIN minutes counted from the start or the last reminder.
 * While the reminder is switched off the count restarts, so switching it on waits a full interval.
 * No UI; the clock is injected.
 */
public final class WaterSchedule {

    private final Clock clock;
    private Instant last;

    public WaterSchedule(Clock clock) {
        this.clock = clock;
        this.last = clock.instant();
    }

    /** True when the reminder is enabled and a full interval has passed since the start or the last reminder. */
    public synchronized boolean due(WaterStore.Config config) {
        var now = clock.instant();
        if (!config.enabled()) {
            last = now;
            return false;
        }
        return !now.isBefore(last.plus(Duration.ofMinutes(Math.max(1, config.everyMin()))));
    }

    /** Restarts the count; call when a reminder has been shown. */
    public synchronized void reminded() {
        last = clock.instant();
    }
}
