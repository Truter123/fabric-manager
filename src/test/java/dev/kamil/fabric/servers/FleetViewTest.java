package dev.kamil.fabric.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FleetViewTest {

    private static final String SHA = "46771ef8f3cdbc0eb1fc3370deae7d7ef5ce925a";
    private static final Instant STARTED = Instant.parse("2026-10-10T08:00:00Z");
    private static final FleetInventory.Server SERVER = new FleetInventory.Server("hetzner", List.of(
            new FleetInventory.ExpectedApp("perkbill", "prod", "perkbill-deployment"),
            new FleetInventory.ExpectedApp("perkbill", "test", "perkbill-test-deployment")));

    private static AppStatus app(String project, String dir, String version, String... healths) {
        var containers = new java.util.ArrayList<ContainerStatus>();
        for (var h : healths) {
            containers.add(new ContainerStatus("c" + containers.size(), "running", STARTED, "null".equals(h) ? null : h,
                    "img"));
        }
        return new AppStatus(project, dir, AppState.UP, version, STARTED, containers);
    }

    private static ServerStatus status(List<AppStatus> apps, Map<String, String> versions) {
        return new ServerStatus("hetzner", true, null, apps, versions);
    }

    @Test
    void matchedAppUsesShortFleetVersion() {
        var rows = FleetView.merge(SERVER, status(
                List.of(app("perkbill-app", "perkbill-deployment", "old1234567", "healthy", "null")),
                Map.of("perkbill-deployment", SHA)));
        var prod = rows.get(0);
        assertEquals("perkbill", prod.project());
        assertEquals("prod", prod.env());
        assertEquals(AppState.UP, prod.state());
        assertEquals("46771ef", prod.version());
        assertEquals(STARTED, prod.startedAt());
        assertEquals("healthy", prod.health());
        assertTrue(prod.managed());
    }

    @Test
    void fallsBackToImageVersion() {
        var rows = FleetView.merge(SERVER, status(
                List.of(app("perkbill-app", "perkbill-deployment", SHA, "null")), Map.of()));
        assertEquals("46771ef", rows.get(0).version());
        assertNull(rows.get(0).health());
    }

    @Test
    void missingStackIsDown() {
        var rows = FleetView.merge(SERVER, status(
                List.of(app("perkbill-app", "perkbill-deployment", SHA, "healthy")), Map.of()));
        var test = rows.get(1);
        assertEquals("test", test.env());
        assertEquals(AppState.DOWN, test.state());
        assertNull(test.startedAt());
        assertTrue(test.managed());
    }

    @Test
    void unreachableServerIsUnknown() {
        var rows = FleetView.merge(SERVER, new ServerStatus("hetzner", false, "timeout", List.of(), Map.of()));
        assertEquals(2, rows.size());
        rows.forEach(r -> assertEquals(AppState.UNKNOWN, r.state()));
    }

    @Test
    void unmanagedStackIsListed() {
        var rows = FleetView.merge(SERVER, status(List.of(
                app("perkbill-app", "perkbill-deployment", SHA, "healthy"),
                app("grafana", "monitoring", null, "healthy", "unhealthy")), Map.of()));
        var extra = rows.get(2);
        assertEquals("grafana", extra.project());
        assertNull(extra.env());
        assertFalse(extra.managed());
        assertEquals("unhealthy", extra.health());
        assertEquals(3, rows.size());
    }
}
