package dev.kamil.fabric.servers;

import java.time.Instant;
import java.util.List;

/** One compose project on a server; {@code remoteDir} (parent dir name of its compose file), {@code version}
 * and {@code startedAt} may be null. */
public record AppStatus(String composeProject, String remoteDir, AppState state, String version, Instant startedAt,
        List<ContainerStatus> containers) {
}
