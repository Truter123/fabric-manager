package dev.kamil.fabric.english;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProgressStoreTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 17);

    @TempDir
    Path dir;

    @BeforeEach
    void point() {
        ProgressStore.file = dir.resolve("sub/flashcards.csv");
    }

    private static Sentence s(String en) {
        return new Sentence("2026-10-01", en, "pl", "", "");
    }

    @Test
    void missingFileLoadsEmpty() throws Exception {
        assertTrue(ProgressStore.load().isEmpty());
    }

    @Test
    void saveKeepsOthersAndDropsDeleted() throws Exception {
        ProgressStore.save(List.of(new CardProgress("A", 1, D), new CardProgress("B", 2, D),
                new CardProgress("Gone", 3, D)), List.of(s("A"), s("B"), s("Gone")));
        ProgressStore.save(List.of(new CardProgress("A", 4, D)), List.of(s("A"), s("B")));
        var loaded = ProgressStore.load();
        assertEquals(List.of("A", "B"), List.copyOf(loaded.keySet()));
        assertEquals(4, loaded.get("A").box());
        assertEquals(2, loaded.get("B").box());
    }
}
