package dev.kamil.fabric.quotes;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Time-based choice of the quote to show; pure logic, no UI. */
public final class QuoteRotation {

    /** The quote to show (null when there are none) and whether it differs from the stored current one. */
    public record Result(Quote quote, boolean advanced) {
    }

    private QuoteRotation() {
    }

    /**
     * Keeps the current quote until the interval since {@code shownAt} is over, then moves to the next
     * (wrapping at the end). An unknown or deleted current id, or a missing shownAt, gives the first quote.
     */
    public static Result resolve(List<Quote> quotes, long currentId, Instant shownAt, Instant now,
            Duration interval) {
        if (quotes.isEmpty()) {
            return new Result(null, false);
        }
        int index = indexOf(quotes, currentId);
        if (index < 0) {
            return new Result(quotes.get(0), true);
        }
        if (shownAt == null || !now.isBefore(shownAt.plus(interval))) {
            return new Result(quotes.get((index + 1) % quotes.size()), true);
        }
        return new Result(quotes.get(index), false);
    }

    private static int indexOf(List<Quote> quotes, long id) {
        for (int i = 0; i < quotes.size(); i++) {
            if (quotes.get(i).id() == id) {
                return i;
            }
        }
        return -1;
    }
}
