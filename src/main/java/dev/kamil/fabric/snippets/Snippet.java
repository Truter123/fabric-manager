package dev.kamil.fabric.snippets;

/** A saved prompt or command; projectPath is null for a global snippet. */
public record Snippet(long id, String name, String text, String projectPath, int useCount) {

    /** The first line of the text, for list rows. */
    public String firstLine() {
        int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end);
    }
}
