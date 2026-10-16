package dev.kamil.fabric.session;

import java.nio.file.Path;
import java.time.Instant;

/** A session row: what ran where, and how it ended. {@code endedAt} and {@code lastStatus} may be null; {@code account} never is. */
public record SavedSession(String id, Path project, Tool tool, String title, Instant startedAt, Instant endedAt,
        String lastStatus, Account account) {
}
