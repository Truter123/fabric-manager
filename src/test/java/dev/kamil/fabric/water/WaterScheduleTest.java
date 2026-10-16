package dev.kamil.fabric.water;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WaterScheduleTest {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-16T08:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final WaterSchedule schedule = new WaterSchedule(clock);
    private final WaterStore.Config on = new WaterStore.Config(250, 60, 2000, true, true, true);

    @Test
    void notDueBeforeTheInterval() {
        clock.now = clock.now.plus(Duration.ofMinutes(59));
        assertFalse(schedule.due(on));
    }

    @Test
    void dueAfterTheInterval() {
        clock.now = clock.now.plus(Duration.ofMinutes(60));
        assertTrue(schedule.due(on));
    }

    @Test
    void remindingRestartsTheCount() {
        clock.now = clock.now.plus(Duration.ofMinutes(61));
        assertTrue(schedule.due(on));
        schedule.reminded();
        assertFalse(schedule.due(on));
        clock.now = clock.now.plus(Duration.ofMinutes(60));
        assertTrue(schedule.due(on));
    }

    @Test
    void neverDueWhenDisabledAndSwitchingOnWaitsAFullInterval() {
        var off = new WaterStore.Config(250, 60, 2000, true, true, false);
        clock.now = clock.now.plus(Duration.ofMinutes(120));
        assertFalse(schedule.due(off));
        assertFalse(schedule.due(on));
        clock.now = clock.now.plus(Duration.ofMinutes(60));
        assertTrue(schedule.due(on));
    }

    @Test
    void shorterIntervalAppliesImmediately() {
        clock.now = clock.now.plus(Duration.ofMinutes(5));
        assertFalse(schedule.due(on));
        assertTrue(schedule.due(new WaterStore.Config(250, 5, 2000, true, true, true)));
    }
}
