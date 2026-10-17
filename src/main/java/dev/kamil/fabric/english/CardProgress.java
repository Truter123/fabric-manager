package dev.kamil.fabric.english;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Leitner box and due date of one flashcard, keyed by its English text. */
public record CardProgress(String english, int box, LocalDate due) {

    private static final String HEADER = "english;box;due";

    /** Parses the progress file; the English text may contain ';' because box and due are read from the right. */
    public static List<CardProgress> parse(String csv) {
        var result = new ArrayList<CardProgress>();
        for (var raw : csv.split("\r?\n", -1)) {
            if (raw.isBlank() || raw.equals(HEADER)) {
                continue;
            }
            int dueAt = raw.lastIndexOf(';');
            int boxAt = dueAt > 0 ? raw.lastIndexOf(';', dueAt - 1) : -1;
            if (boxAt <= 0) {
                continue;
            }
            try {
                var english = raw.substring(0, boxAt);
                int box = Integer.parseInt(raw.substring(boxAt + 1, dueAt).strip());
                var due = LocalDate.parse(raw.substring(dueAt + 1).strip());
                if (english.isBlank() || box < 1) {
                    continue;
                }
                result.add(new CardProgress(english, box, due));
            } catch (RuntimeException e) {
                // bad line: skipped
            }
        }
        return result;
    }

    public static String format(List<CardProgress> cards) {
        var out = new StringBuilder(HEADER).append('\n');
        for (var c : cards) {
            out.append(c.english()).append(';').append(c.box()).append(';').append(c.due()).append('\n');
        }
        return out.toString();
    }

    public static List<CardProgress> load(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Replaces the file atomically. */
    public static void save(Path file, List<CardProgress> cards) throws IOException {
        var dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        var tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, format(cards), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
