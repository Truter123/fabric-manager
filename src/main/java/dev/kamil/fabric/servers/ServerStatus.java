package dev.kamil.fabric.servers;

import java.util.List;
import java.util.Map;

/** Result of one probe; {@code fleetVersions} maps a fleet-state directory to its git SHA. */
public record ServerStatus(String alias, boolean reachable, String error, List<AppStatus> apps,
        Map<String, String> fleetVersions) {
}
