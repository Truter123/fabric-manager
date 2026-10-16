package dev.kamil.fabric.tasks;

/** Number of open tasks that are overdue and that are due today. */
public record DueCount(int overdue, int today) {
}
