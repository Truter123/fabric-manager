package dev.kamil.fabric.snippets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FuzzyTest {

    @Test
    void nonSubsequenceDoesNotMatch() {
        assertEquals(-1, Fuzzy.score("xyz", "review", "Review the diff"));
    }

    @Test
    void subsequenceMatches() {
        assertTrue(Fuzzy.score("rvw", "review", "") >= 0);
    }

    @Test
    void consecutiveBeatsScattered() {
        assertTrue(Fuzzy.score("com", "commit") > Fuzzy.score("com", "cxoxm"));
    }

    @Test
    void wordStartBeatsMidWord() {
        assertTrue(Fuzzy.score("d", "big diff") > Fuzzy.score("d", "wide"));
    }

    @Test
    void nameMatchBeatsTextMatch() {
        assertTrue(Fuzzy.score("rev", "review", "x") > Fuzzy.score("rev", "x", "review"));
    }

    @Test
    void blankQueryMatchesEverything() {
        assertEquals(0, Fuzzy.score(" ", "a", "b"));
    }
}
