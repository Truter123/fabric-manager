package dev.kamil.fabric.english;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SentenceFileTest {

    private static final String SAMPLE = """
            # Everyday sentences

            ### 2026-10-14
            English: I want to learn English every day.
            PL: Chcę uczyć się angielskiego codziennie.
            Say: `aj-WANNA-LERN‿YNG-lysz | EW-ri-DEJ`
            Watch: want **to** learn — "want" always takes "to". You wrote: "i want lern"

            ### 2026-10-15
            English: Second one.
            PL: Drugie.
            Say: `SEK-ynd`
            Watch: plain **x** text
            """;

    @Test
    void parsesTwoBlocks() {
        var list = SentenceFile.parse(SAMPLE);
        assertEquals(2, list.size());
        var first = list.get(0);
        assertEquals("2026-10-14", first.date());
        assertEquals("I want to learn English every day.", first.english());
        assertEquals("Chcę uczyć się angielskiego codziennie.", first.pl());
        assertEquals("aj-WANNA-LERN‿YNG-lysz | EW-ri-DEJ", first.say());
        assertTrue(first.watch().contains("**to**"));
        assertTrue(!first.watchPlain().contains("**"));
        assertEquals("Second one.", list.get(1).english());
    }

    @Test
    void missingFieldIsEmpty() {
        var list = SentenceFile.parse("# T\n\n### 2026-01-01\nEnglish: Hi\nSay: `x`\nWatch: w\n");
        assertEquals("", list.get(0).pl());
    }

    @Test
    void formatReproducesText() {
        assertEquals(SAMPLE, SentenceFile.format(SentenceFile.parse(SAMPLE)));
    }

    @Test
    void saveWritesBackupAndNewContent(@TempDir Path dir) throws IOException {
        var file = dir.resolve("everyday.md");
        Files.writeString(file, SAMPLE);
        var list = SentenceFile.load(file);
        var changed = List.of(new Sentence("2026-02-02", "E", "P", "S", "W"));
        SentenceFile.save(file, changed);
        assertEquals(SAMPLE, Files.readString(dir.resolve("everyday.md.bak")));
        assertEquals(changed, SentenceFile.load(file));
        assertEquals(2, list.size());
    }
}
