package dev.kamil.fabric.english;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LearningTest {

    private static Sentence s(String en, String pl) {
        return new Sentence("2026-10-01", en, pl, "", "");
    }

    @Test
    void unlearnedKeepsFileOrderAndSkipsSavedBlankAndDuplicates() {
        var list = List.of(s("A", "a"), s("B", "b"), s("", "x"), s("C", " "), s("A", "again"), s("D", "d"));
        var saved = Map.of("B", new CardProgress("B", 1, LocalDate.of(2026, 10, 17)));
        assertEquals(List.of(list.get(0), list.get(5)), Learning.unlearned(list, saved));
    }

    @Test
    void learnedIsBoxOneDueTomorrow() {
        var today = LocalDate.of(2026, 10, 17);
        assertEquals(new CardProgress("A", 1, today.plusDays(1)), Learning.learned("A", today));
    }
}
