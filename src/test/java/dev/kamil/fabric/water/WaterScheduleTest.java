package dev.kamil.fabric.water;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @TempDir
    Path tmp;

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

    @Test
    void neverRemindsWithoutAnEnabledLineInTheConfig() throws IOException {
        Files.writeString(tmp.resolve("config"), "ML=250\nEVERY_MIN=60\nGOAL_ML=2000\nNOTIFY=1\nPOPUP=1\n");
        var config = new WaterStore(tmp).readConfig();
        clock.now = clock.now.plus(Duration.ofMinutes(600));
        assertFalse(schedule.due(config));
        assertFalse(schedule.due(WaterStore.Config.DEFAULT));
    }
}
