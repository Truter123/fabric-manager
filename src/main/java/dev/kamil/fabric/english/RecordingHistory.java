package dev.kamil.fabric.english;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Voice recording attempts per sentence: one directory per sentence, one timestamped WAV per attempt; no UI. */
final class RecordingHistory {

    /** Attempts kept per sentence by the windows. */
    static final int KEEP = 30;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final int STAMP_LENGTH = "yyyy-MM-dd_HH-mm-ss".length();
    private static final String EXTENSION = ".wav";

    /** One saved recording and when it was made. */
    record Attempt(Path file, LocalDateTime at) { }

    private final Path root;

    RecordingHistory(Path root) {
        this.root = root;
    }

    /** Root of the production history; tests point it at a temporary directory. */
    static Path standardRoot = Path.of("/home/kamil/Documents/Tools/english/recordings");

    /** The production history under the english folder. */
    static RecordingHistory standard() {
        return new RecordingHistory(standardRoot);
    }

    private Path dir(String english) {
        try {
            var sha = MessageDigest.getInstance("SHA-1").digest(english.strip().getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (byte b : sha) {
                hex.append(String.format("%02x", b));
            }
            return root.resolve(hex.substring(0, 12));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The path for a new attempt (directories created, {@code sentence.txt} written on first use). */
    Path newFile(String english, LocalDateTime now) throws IOException {
        var dir = dir(english);
        Files.createDirectories(dir);
        var text = dir.resolve("sentence.txt");
        if (!Files.exists(text)) {
            Files.writeString(text, english.strip() + "\n");
        }
        var stamp = STAMP.format(now);
        var file = dir.resolve(stamp + EXTENSION);
        // two recordings in the same second get -2, -3, ... instead of overwriting each other
        for (int n = 2; Files.exists(file); n++) {
            file = dir.resolve(stamp + "-" + n + EXTENSION);
        }
        return file;
    }

    /** All attempts of the sentence, newest first; empty when none. */
    List<Attempt> list(String english) {
        var dir = dir(english);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .map(RecordingHistory::attempt)
                    .filter(a -> a != null)
                    .sorted(Comparator.comparing(Attempt::at).reversed())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** "today 14:02", "yesterday 09:10" or "2026-10-12 18:30". */
    static String label(LocalDateTime at) {
        var time = String.format("%02d:%02d", at.getHour(), at.getMinute());
        var today = java.time.LocalDate.now();
        if (at.toLocalDate().equals(today)) {
            return "today " + time;
        }
        if (at.toLocalDate().equals(today.minusDays(1))) {
            return "yesterday " + time;
        }
        return at.toLocalDate() + " " + time;
    }

    private static Attempt attempt(Path file) {
        var name = file.getFileName().toString();
        if (!name.endsWith(EXTENSION)) {
            return null;
        }
        var base = name.substring(0, name.length() - EXTENSION.length());
        long repeat = 0;
        var dash = base.indexOf('-', STAMP_LENGTH);
        try {
            if (dash >= 0) {
                repeat = Long.parseLong(base.substring(dash + 1)) - 1;
                base = base.substring(0, dash);
            }
            // the repeat number orders same-second recordings
            return new Attempt(file, LocalDateTime.parse(base, STAMP).plusNanos(repeat * 1_000_000));
        } catch (DateTimeParseException | NumberFormatException e) {
            return null;
        }
    }

    int count(String english) {
        return list(english).size();
    }

    void delete(Path file) throws IOException {
        Files.deleteIfExists(file);
    }

    /** Deletes the oldest attempts beyond {@code keep}. */
    void prune(String english, int keep) throws IOException {
        var all = list(english);
        for (int i = keep; i < all.size(); i++) {
            Files.deleteIfExists(all.get(i).file());
        }
    }
}
