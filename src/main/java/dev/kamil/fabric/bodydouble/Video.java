package dev.kamil.fabric.bodydouble;

import java.time.Instant;

/** A video the user has played in the body double window. */
public record Video(long id, String url, String title, long positionMs, Instant lastPlayedAt) {

    /** Title for lists: the stored title, or the URL when none is known. */
    public String label() {
        return title == null || title.isBlank() ? url : title;
    }
}
