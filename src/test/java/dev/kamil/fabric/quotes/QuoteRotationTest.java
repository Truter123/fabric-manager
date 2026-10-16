package dev.kamil.fabric.quotes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteRotationTest {

    private static final Duration HOUR = Duration.ofMinutes(60);
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private final List<Quote> quotes = List.of(new Quote(1, "a", "A"), new Quote(5, "b", "B"), new Quote(9, "c", null));

    @Test
    void notDueKeepsTheSameQuote() {
        var result = QuoteRotation.resolve(quotes, 5, T0, T0.plus(Duration.ofMinutes(59)), HOUR);

        assertEquals(5, result.quote().id());
        assertFalse(result.advanced());
    }

    @Test
    void dueMovesToTheNextQuote() {
        var result = QuoteRotation.resolve(quotes, 1, T0, T0.plus(HOUR), HOUR);

        assertEquals(5, result.quote().id());
        assertTrue(result.advanced());
    }

    @Test
    void wrapsAtTheEnd() {
        var result = QuoteRotation.resolve(quotes, 9, T0, T0.plus(Duration.ofHours(3)), HOUR);

        assertEquals(1, result.quote().id());
        assertTrue(result.advanced());
    }

    @Test
    void deletedCurrentIdGivesTheFirst() {
        var result = QuoteRotation.resolve(quotes, 7, T0, T0, HOUR);

        assertEquals(1, result.quote().id());
        assertTrue(result.advanced());
    }

    @Test
    void emptyListGivesNone() {
        var result = QuoteRotation.resolve(List.of(), 1, T0, T0, HOUR);

        assertNull(result.quote());
        assertFalse(result.advanced());
    }
}
