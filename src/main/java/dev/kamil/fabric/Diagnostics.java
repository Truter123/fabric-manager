package dev.kamil.fabric;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Stderr logging for background loops: each distinct message is printed once, so a poll does not spam. */
public final class Diagnostics {

    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private Diagnostics() {
    }

    /** Prints {@code message} to stderr unless it was printed before; true when it was printed. */
    public static boolean warnOnce(String message) {
        if (!SEEN.add(message)) {
            return false;
        }
        System.err.println(message);
        return true;
    }
}
