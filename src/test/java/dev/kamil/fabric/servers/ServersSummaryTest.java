package dev.kamil.fabric.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServersSummaryTest {

    private static final ServerStatus OK = new ServerStatus("hetzner", true, null, List.of(), Map.of());
    private static final ServerStatus DOWN = new ServerStatus("nlp", false, "Connection refused", List.of(),
            Map.of());

    private static FleetView.AppRow row(AppState state, boolean managed) {
        return new FleetView.AppRow("perkbill", managed ? "prod" : null, state, null, null, null, managed);
    }

    @Test
    void notCheckedIsGrey() {
        assertEquals(ServersSummary.Dot.GREY, ServersSummary.dot(null, List.of()));
        assertEquals("not checked yet", ServersSummary.text(null, Instant.EPOCH, ZoneId.of("UTC")));
    }

    @Test
    void unreachableIsRed() {
        assertEquals(ServersSummary.Dot.RED, ServersSummary.dot(DOWN, List.of(row(AppState.UNKNOWN, true))));
        assertEquals("unreachable: Connection refused", ServersSummary.text(DOWN, Instant.EPOCH, ZoneId.of("UTC")));
    }

    @Test
    void anyDownIsRedEvenWithDegraded() {
        assertEquals(ServersSummary.Dot.RED,
                ServersSummary.dot(OK, List.of(row(AppState.UP, true), row(AppState.DEGRADED, true),
                        row(AppState.DOWN, true))));
    }

    @Test
    void degradedIsAmberAndAllUpIsGreen() {
        assertEquals(ServersSummary.Dot.AMBER,
                ServersSummary.dot(OK, List.of(row(AppState.UP, true), row(AppState.DEGRADED, true))));
        assertEquals(ServersSummary.Dot.GREEN, ServersSummary.dot(OK, List.of(row(AppState.UP, true))));
    }

    @Test
    void checkedTimeAndAppName() {
        assertEquals("checked 12:34:05",
                ServersSummary.text(OK, Instant.parse("2026-10-16T12:34:05Z"), ZoneId.of("UTC")));
        assertEquals("perkbill", ServersSummary.appName(row(AppState.UP, true)));
        assertEquals("perkbill (unmanaged)", ServersSummary.appName(row(AppState.UP, false)));
    }
}
