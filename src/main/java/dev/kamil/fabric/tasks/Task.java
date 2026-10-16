package dev.kamil.fabric.tasks;

import java.time.Instant;
import java.time.LocalDate;

/** A task with an optional due date; doneAt is null while it is open. */
public record Task(long id, String text, LocalDate due, Instant doneAt, Instant createdAt) {

    public boolean done() {
        return doneAt != null;
    }
}
