package dev.kamil.fabric.english;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Which sentences are not learned yet; a sentence is learned when it has a row in the progress file. */
public final class Learning {

    private Learning() {
    }

    /** Sentences without saved progress, in file order, without blank or duplicate English. */
    static List<Sentence> unlearned(List<Sentence> sentences, Map<String, CardProgress> saved) {
        var seen = new HashSet<String>();
        var result = new ArrayList<Sentence>();
        for (var s : sentences) {
            if (s.english() == null || s.english().isBlank() || s.pl() == null || s.pl().isBlank()) {
                continue;
            }
            if (seen.add(s.english()) && !saved.containsKey(s.english())) {
                result.add(s);
            }
        }
        return result;
    }

    /** Progress of a sentence learned today: box 1, due tomorrow. */
    static CardProgress learned(String english, LocalDate today) {
        return new CardProgress(english, 1, today.plusDays(1));
    }
}
