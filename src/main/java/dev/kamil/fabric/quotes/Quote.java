package dev.kamil.fabric.quotes;

/** One motivating sentence with its author (may be null). */
public record Quote(long id, String text, String author) {
}
