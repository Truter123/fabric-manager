package dev.kamil.fabric.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;

class SynchronizedOutputReaderTest {

    /** Hands out one prepared string per read call. */
    private static Reader chunks(String... parts) {
        var queue = new ArrayDeque<String>(java.util.List.of(parts));
        return new Reader() {
            @Override
            public int read(char[] buf, int off, int len) {
                var next = queue.poll();
                if (next == null) {
                    return -1;
                }
                next.getChars(0, next.length(), buf, off);
                return next.length();
            }

            @Override
            public void close() throws IOException {
            }
        };
    }

    private static String readAll(SynchronizedOutputReader reader) throws IOException {
        var out = new StringBuilder();
        var buf = new char[64];
        int n;
        while ((n = reader.read(buf, 0, buf.length)) >= 0) {
            out.append(buf, 0, n);
        }
        return out.toString();
    }

    @Test
    void beginMarkerSplitAcrossTwoReadsIsRemoved() throws IOException {
        var reader = new SynchronizedOutputReader(chunks("a\u001b[?20", "26hb\u001b[?2026lc"));
        assertEquals("abc", readAll(reader));
    }

    @Test
    void partialMarkerThatNeverCompletesIsKept() throws IOException {
        var reader = new SynchronizedOutputReader(chunks("a\u001b[?20", "xb"));
        assertEquals("a\u001b[?20xb", readAll(reader));
    }
}
