package dev.kamil.fabric.english;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SentenceListTest {

    @Test
    void theListIsMyListInTheEnglishFolder(@TempDir Path dir) {
        var list = SentenceList.of(dir);
        assertEquals(dir.resolve("my-list.md"), list.sentences());
        assertEquals(dir.resolve("my-list.removed.md"), list.removed());
        assertEquals(dir.resolve("my-list.progress.csv"), list.progress());
    }
}
