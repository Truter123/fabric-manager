package dev.kamil.fabric.notes;

import java.time.Instant;

/**
 * A saved note. Markdown body in text; tags are the lowercase #words of the body, space separated;
 * projectPath is the linked project folder or null; corrected is null until the corrected English arrives.
 */
public record Note(long id, String title, String text, String corrected, String tags, boolean pinned,
        boolean inbox, String projectPath, Instant createdAt, Instant updatedAt) {

    /** The title, or the first non-blank line of the body when the note has no title. */
    public String displayTitle() {
        if (title != null && !title.isBlank()) {
            return title;
        }
        return firstLine(text);
    }

    /** The first non-blank body line, unless it is the display title, then the next one. */
    public String preview() {
        var lines = text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        boolean titled = title != null && !title.isBlank();
        int index = titled ? 0 : 1;
        return index < lines.size() ? lines.get(index) : "";
    }

    /** The tags as a list. */
    public java.util.List<String> tagList() {
        return tags == null || tags.isBlank() ? java.util.List.of() : java.util.List.of(tags.split(" "));
    }

    private static String firstLine(String body) {
        return body.lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("");
    }
}
