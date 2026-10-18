package dev.kamil.fabric.english;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads and writes the flashcards progress file. */
public final class ProgressStore {

    /** The progress file; tests point it at a temporary file. */
    static Path file = Path.of("/home/kamil/Documents/Tools/english/flashcards.csv");

    private ProgressStore() {
    }

    /** Saved progress by English text; empty when the file does not exist. */
    static Map<String, CardProgress> load() throws IOException {
        var result = new LinkedHashMap<String, CardProgress>();
        if (Files.exists(file)) {
            for (var p : CardProgress.load(file)) {
                result.put(p.english(), p);
            }
        }
        return result;
    }

    /** Saves {@code changed} and keeps the saved rows of other sentences that still exist in {@code all}. */
    static void save(List<CardProgress> changed, List<Sentence> all) throws IOException {
        var merged = new ArrayList<>(changed);
        if (Files.exists(file)) {
            var inChanged = new HashSet<String>();
            merged.forEach(p -> inChanged.add(p.english()));
            var known = new HashSet<String>();
            all.forEach(sentence -> known.add(sentence.english()));
            for (var p : CardProgress.load(file)) {
                if (!inChanged.contains(p.english()) && known.contains(p.english())) {
                    merged.add(p);
                }
            }
        }
        CardProgress.save(file, merged);
    }

    /** Removes the saved row of {@code english}, if any, so the sentence is new again. */
    static void forget(String english) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        var kept = new ArrayList<CardProgress>();
        for (var p : CardProgress.load(file)) {
            if (!p.english().equals(english)) {
                kept.add(p);
            }
        }
        CardProgress.save(file, kept);
    }
}
