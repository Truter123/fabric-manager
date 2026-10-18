package dev.kamil.fabric.servers;

import java.time.Duration;

/** Formats how long an app has been running. */
public final class Uptime {

    private Uptime() {
    }

    /** "3d 4h", "5h 12m" or "42m"; a negative duration counts as zero. */
    public static String format(Duration duration) {
        var minutes = Math.max(0, duration.toMinutes());
        var days = minutes / 1440;
        var hours = minutes % 1440 / 60;
        var rest = minutes % 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + rest + "m";
        }
        return rest + "m";
    }
}
