package dev.kamil.fabric.claude.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranscriptUsageTest {

    @TempDir
    Path dir;

    private Path fixture(String name) throws IOException {
        try (var in = getClass().getResourceAsStream("/usage/" + name)) {
            var target = dir.resolve(name);
            Files.write(target, in.readAllBytes());
            return target;
        }
    }

    @Test
    void duplicateMessageIdKeepsLastLine() throws IOException {
        var usage = new TranscriptUsage(fixture("session.jsonl")).refresh();
        assertEquals(2, usage.turns().size());
        assertEquals(50 + 20, usage.totalOutputTokens());
    }

    @Test
    void contextIsLastTurnInputPlusCaches() throws IOException {
        var usage = new TranscriptUsage(fixture("session.jsonl")).refresh();
        assertEquals(3 + 100 + 1500, usage.contextTokens());
        assertEquals(1_000_000, usage.window());
        assertEquals(1603 * 100.0 / 1_000_000, usage.contextPercent(), 1e-9);
    }

    @Test
    void windowIsOneMillionForTaggedModelOpusFiveOrLargeContext() {
        assertEquals(1_000_000, TranscriptUsage.windowFor("claude-sonnet-5-5[1m]", 10));
        assertEquals(1_000_000, TranscriptUsage.windowFor("claude-opus-5-5", 10));
        assertEquals(1_000_000, TranscriptUsage.windowFor("claude-sonnet-5-5", 250_000));
        assertEquals(200_000, TranscriptUsage.windowFor("claude-sonnet-5-5", 200_000));
    }

    @Test
    void refreshReadsOnlyAppendedLinesAndSkipsPartialLine() throws IOException {
        var file = fixture("session.jsonl");
        var usage = new TranscriptUsage(file).refresh();
        long first = usage.offset();
        assertEquals(Files.size(file), first);

        var more = Files.readString(Path.of(fixture("more.jsonl").toString()));
        var half = more.substring(0, 40);
        Files.writeString(file, half, StandardOpenOption.APPEND);
        usage.refresh();
        assertEquals(first, usage.offset());
        assertEquals(2, usage.turns().size());

        Files.writeString(file, more.substring(40), StandardOpenOption.APPEND);
        usage.refresh();
        assertEquals(3, usage.turns().size());
        assertEquals(2001, usage.contextTokens());
        assertEquals(Files.size(file), usage.offset());
    }
}
