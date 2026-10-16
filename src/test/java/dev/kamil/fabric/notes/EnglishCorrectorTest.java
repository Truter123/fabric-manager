package dev.kamil.fabric.notes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class EnglishCorrectorTest {

    @Test
    void returnsStrippedStdout() throws IOException {
        var corrector = new EnglishCorrector(List.of("sh", "-c", "cat >/dev/null; echo Fixed."));

        assertEquals("Fixed.", corrector.correct("fix me"));
    }

    @Test
    void failureCarriesStderr() {
        var corrector = new EnglishCorrector(List.of("sh", "-c", "echo bad >&2; exit 3"));

        var e = assertThrows(IOException.class, () -> corrector.correct("x"));

        assertTrue(e.getMessage().contains("bad"));
    }

    @Test
    void passesTextThroughStdin() throws IOException {
        var corrector = new EnglishCorrector(List.of("cat"));

        assertEquals("hello there", corrector.correct("hello there\n"));
    }
}
