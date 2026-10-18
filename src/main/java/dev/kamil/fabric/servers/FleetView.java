package dev.kamil.fabric.servers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Merges the expected apps of a server with what its probe found. */
public final class FleetView {

    /** One table row; {@code env} is null and {@code managed} false for a stack no fleet.env declares. */
    public record AppRow(String project, String env, AppState state, String version, Instant startedAt,
            String health, boolean managed) {
    }

    private static final int SHORT_SHA = 7;

    private FleetView() {
    }

    public static List<AppRow> merge(FleetInventory.Server server, ServerStatus status) {
        var rows = new ArrayList<AppRow>();
        var matched = new HashSet<String>();
        for (var expected : server.apps()) {
            var app = status.apps().stream()
                    .filter(a -> expected.remoteDir().equals(a.remoteDir())).findFirst().orElse(null);
            if (app == null) {
                var state = status.reachable() ? AppState.DOWN : AppState.UNKNOWN;
                rows.add(new AppRow(expected.project(), expected.env(), state, shortSha(
                        status.fleetVersions().get(expected.remoteDir())), null, null, true));
                continue;
            }
            matched.add(app.composeProject());
            var version = status.fleetVersions().get(expected.remoteDir());
            rows.add(new AppRow(expected.project(), expected.env(), app.state(),
                    shortSha(version != null ? version : app.version()), app.startedAt(), health(app), true));
        }
        for (var app : status.apps()) {
            if (!matched.contains(app.composeProject())) {
                rows.add(new AppRow(app.composeProject(), null, app.state(), shortSha(app.version()),
                        app.startedAt(), health(app), false));
            }
        }
        return rows;
    }

    private static String shortSha(String sha) {
        return sha == null ? null : sha.substring(0, Math.min(SHORT_SHA, sha.length()));
    }

    /** "healthy" when every health-checked container is, else the worst seen; null with no health checks. */
    private static String health(AppStatus app) {
        var seen = app.containers().stream().map(ContainerStatus::health).filter(h -> h != null).toList();
        if (seen.isEmpty()) {
            return null;
        }
        for (var bad : List.of("unhealthy", "starting")) {
            if (seen.contains(bad)) {
                return bad;
            }
        }
        return seen.stream().allMatch("healthy"::equals) ? "healthy" : seen.get(0);
    }
}
