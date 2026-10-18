package dev.kamil.fabric.servers;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.List;

/** UI-independent text and colors for the Servers window. */
public final class ServersSummary {

    /** Dot color of a server. */
    public enum Dot { GREEN, AMBER, RED, GREY }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private ServersSummary() {
    }

    /** Grey before the first check, red when unreachable or any row is DOWN, amber when any is DEGRADED
     * (or UNKNOWN), green otherwise. */
    public static Dot dot(ServerStatus status, List<FleetView.AppRow> rows) {
        if (status == null) {
            return Dot.GREY;
        }
        if (!status.reachable()) {
            return Dot.RED;
        }
        if (rows.stream().anyMatch(r -> r.state() == AppState.DOWN)) {
            return Dot.RED;
        }
        if (rows.stream().anyMatch(r -> r.state() == AppState.DEGRADED || r.state() == AppState.UNKNOWN)) {
            return Dot.AMBER;
        }
        return Dot.GREEN;
    }

    /** "unreachable: <error>", "checked 12:34:05" or "not checked yet". */
    public static String text(ServerStatus status, Instant checkedAt, ZoneId zone) {
        if (status == null) {
            return "not checked yet";
        }
        if (!status.reachable()) {
            return "unreachable: " + status.error();
        }
        return "checked " + TIME.format(checkedAt.atZone(zone));
    }

    /** The App column: the project, or the compose name with "(unmanaged)". */
    public static String appName(FleetView.AppRow row) {
        return row.managed() ? row.project() : row.project() + " (unmanaged)";
    }
}
