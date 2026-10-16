package dev.kamil.fabric.terminal;

import java.io.IOException;
import java.io.Reader;

/**
 * Adds DEC mode 2026 (synchronized output), which JediTermFX does not handle.
 *
 * <p>Claude Code wraps every repaint in {@code ESC[?2026h ... ESC[?2026l}. Text between the two markers is held
 * back and handed to the emulator as one chunk, so a half-drawn frame never reaches the screen. The markers
 * themselves are removed.
 */
final class SynchronizedOutputReader {

    private static final String BEGIN = "\u001b[?2026h";
    private static final String END = "\u001b[?2026l";
    /** Safety valve: a frame this big is released even without END. */
    private static final int MAX_HELD = 1 << 20;

    private final Reader in;
    private final char[] chunk = new char[8192];
    private final StringBuilder ready = new StringBuilder();
    private final StringBuilder pending = new StringBuilder();
    private boolean syncing;

    SynchronizedOutputReader(Reader in) {
        this.in = in;
    }

    int read(char[] buf, int offset, int length) throws IOException {
        while (ready.isEmpty()) {
            int n = in.read(chunk);
            if (n < 0) {
                ready.append(pending);
                pending.setLength(0);
                if (ready.isEmpty()) {
                    return -1;
                }
                break;
            }
            pending.append(chunk, 0, n);
            drain();
        }
        int count = Math.min(length, ready.length());
        ready.getChars(0, count, buf, offset);
        ready.delete(0, count);
        return count;
    }

    boolean ready() throws IOException {
        return !ready.isEmpty() || in.ready();
    }

    /** Moves everything that may be shown now from {@code pending} to {@code ready}. */
    private void drain() {
        while (true) {
            var marker = syncing ? END : BEGIN;
            int at = pending.indexOf(marker);
            if (at >= 0) {
                ready.append(pending, 0, at);
                pending.delete(0, at + marker.length());
                syncing = !syncing;
                continue;
            }
            if (syncing) {
                if (pending.length() > MAX_HELD) {
                    ready.append(pending);
                    pending.setLength(0);
                    syncing = false;
                }
                return;
            }
            // Outside a frame: show all but a tail that could be the start of a split BEGIN marker.
            int keep = partialMarkerLength(pending, BEGIN);
            ready.append(pending, 0, pending.length() - keep);
            pending.delete(0, pending.length() - keep);
            return;
        }
    }

    private static int partialMarkerLength(CharSequence text, String marker) {
        for (int len = Math.min(marker.length() - 1, text.length()); len > 0; len--) {
            if (tailStartsMarker(text, marker, len)) {
                return len;
            }
        }
        return 0;
    }

    /** True when the last {@code len} chars of {@code text} equal the first {@code len} chars of {@code marker}. */
    private static boolean tailStartsMarker(CharSequence text, String marker, int len) {
        int from = text.length() - len;
        for (int i = 0; i < len; i++) {
            if (text.charAt(from + i) != marker.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
