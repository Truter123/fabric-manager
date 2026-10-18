package dev.kamil.fabric.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProbeParserTest {

    private static final String SHA = "46771ef8f3cdbc0eb1fc3370deae7d7ef5ce925a";
    private static final String LS_ARRAY = "[{\"Name\":\"perkbill-app\",\"Status\":\"running(4)\",\"ConfigFiles\":"
            + "\"/home/admin/perkbill-deployment/deploy-base.yml,/home/admin/perkbill-deployment/docker-compose.yml\"},"
            + "{\"Name\":\"perkbill-test-app\",\"Status\":\"running(4)\",\"ConfigFiles\":"
            + "\"/home/admin/perkbill-test-deployment/deploy-base.yml\"}]";
    private static final String LS_LINES = "{\"Name\":\"perkbill-app\",\"Status\":\"running(4)\"}\n"
            + "{\"Name\":\"perkbill-test-app\",\"Status\":\"running(4)\"}";
    private static final String FLEET = "perkbill-deployment " + SHA;

    private static String container(String name, String state, String started, String health, String project) {
        return "\"/" + name + "\" \"" + state + "\" \"" + started + "\" " + health
                + " \"kamil/perkbill:backend-" + SHA + "\" \"" + project + "\"";
    }

    private static final String BACKEND = container("perkbill-app-backend", "running",
            "2026-10-11T15:34:51.369897Z", "\"healthy\"", "perkbill-app");
    private static final String DB = container("perkbill-app-db", "running",
            "2026-10-10T08:00:00.000000Z", "null", "perkbill-app");

    private static ServerStatus parse(String ls, String inspect) {
        return ProbeParser.parse("hetzner", new ServerProbe.Output(ls + "\n---\n" + inspect + "\n---\n" + FLEET + "\n",
                0, ""));
    }

    private static AppStatus app(ServerStatus status, String name) {
        return status.apps().stream().filter(a -> a.composeProject().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void allHealthyIsUpWithEarliestStartAndVersion() {
        var status = parse(LS_ARRAY, BACKEND + "\n" + DB);
        assertTrue(status.reachable());
        var app = app(status, "perkbill-app");
        assertEquals(AppState.UP, app.state());
        assertEquals(Instant.parse("2026-10-10T08:00:00Z"), app.startedAt());
        assertEquals(SHA, app.version());
        assertEquals("perkbill-deployment", app.remoteDir());
        assertEquals("perkbill-test-deployment", app(status, "perkbill-test-app").remoteDir());
        assertEquals("perkbill-app-backend", app.containers().get(0).name());
        assertEquals("healthy", app.containers().get(0).health());
        assertNull(app.containers().get(1).health());
        assertEquals(SHA, status.fleetVersions().get("perkbill-deployment"));
    }

    @Test
    void stackWithoutContainersIsDown() {
        var status = parse(LS_ARRAY, BACKEND + "\n" + DB);
        var test = app(status, "perkbill-test-app");
        assertEquals(AppState.DOWN, test.state());
        assertNull(test.startedAt());
        assertNull(test.version());
    }

    @Test
    void oneExitedIsDegraded() {
        var exited = container("perkbill-app-db", "exited", "2026-10-10T08:00:00Z", "null", "perkbill-app");
        var app = app(parse(LS_ARRAY, BACKEND + "\n" + exited), "perkbill-app");
        assertEquals(AppState.DEGRADED, app.state());
        assertEquals(Instant.parse("2026-10-11T15:34:51.369897Z"), app.startedAt());
    }

    @Test
    void allExitedIsDown() {
        var a = container("perkbill-app-backend", "exited", "2026-10-11T15:34:51Z", "null", "perkbill-app");
        var b = container("perkbill-app-db", "exited", "2026-10-10T08:00:00Z", "null", "perkbill-app");
        var app = app(parse(LS_ARRAY, a + "\n" + b), "perkbill-app");
        assertEquals(AppState.DOWN, app.state());
        assertNull(app.startedAt());
    }

    @Test
    void unhealthyIsDegraded() {
        var sick = container("perkbill-app-backend", "running", "2026-10-11T15:34:51Z", "\"unhealthy\"",
                "perkbill-app");
        assertEquals(AppState.DEGRADED, app(parse(LS_ARRAY, sick + "\n" + DB), "perkbill-app").state());
    }

    @Test
    void jsonLinesComposeListIsAccepted() {
        var status = parse(LS_LINES, BACKEND);
        assertEquals(2, status.apps().size());
        assertEquals(AppState.UP, app(status, "perkbill-app").state());
    }

    @Test
    void sshExit255IsUnreachable() {
        var status = ProbeParser.parse("hetzner", new ServerProbe.Output("", 255,
                "ssh: connect to host x port 22: Connection refused"));
        assertFalse(status.reachable());
        assertTrue(status.apps().isEmpty());
        assertTrue(status.error().contains("Connection refused"));
    }

    @Test
    void timeoutIsUnreachable() {
        var status = ProbeParser.parse("hetzner", new ServerProbe.Output("", ServerProbe.TIMEOUT, "timed out"));
        assertFalse(status.reachable());
        assertEquals("timed out", status.error());
    }
}
