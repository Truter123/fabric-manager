package dev.kamil.fabric.english;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CardProgressTest {

    private static final List<CardProgress> CARDS = List.of(
            new CardProgress("I want to learn.", 2, LocalDate.of(2026, 10, 17)),
            new CardProgress("Well; I think so; really", 5, LocalDate.of(2026, 11, 1)));

    @Test
    void roundTripKeepsSemicolonsInEnglish() {
        assertEquals(CARDS, CardProgress.parse(CardProgress.format(CARDS)));
    }

    @Test
    void badLinesAreSkipped() {
        var csv = "english;box;due\nok;2;2026-10-17\nno fields\nx;abc;2026-10-17\ny;1;not-a-date\n\n;1;2026-10-17\nz;0;2026-10-17\n";
        assertEquals(List.of(new CardProgress("ok", 2, LocalDate.of(2026, 10, 17))), CardProgress.parse(csv));
    }

    @Test
    void saveThenLoad(@TempDir Path dir) throws IOException {
        var file = dir.resolve("sub/flashcards.csv");
        CardProgress.save(file, CARDS);
        assertEquals(CARDS, CardProgress.load(file));
    }
}
