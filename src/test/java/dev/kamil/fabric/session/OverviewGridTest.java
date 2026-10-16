package dev.kamil.fabric.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;

class OverviewGridTest {

    @Test
    void trimKeepsLastNonBlankLines() {
        assertEquals(List.of("b", "c", "d"), OverviewGrid.trim(List.of("a", "b", "  ", "c", "d  ", "", " "), 3));
        assertEquals(List.of("x"), OverviewGrid.trim(List.of("", "x", ""), 3));
        assertEquals(List.of(), OverviewGrid.trim(List.of(), 3));
    }

    @Test
    void numberKeysPickCards() {
        assertEquals(0, OverviewGrid.keyToIndex("1", 3));
        assertEquals(2, OverviewGrid.keyToIndex("3", 3));
        assertEquals(-1, OverviewGrid.keyToIndex("4", 3));
        assertEquals(-1, OverviewGrid.keyToIndex("0", 3));
        assertEquals(-1, OverviewGrid.keyToIndex("a", 3));
        assertEquals(-1, OverviewGrid.keyToIndex(null, 3));
    }

    @Test
    void arrowsMoveAndClamp() {
        assertEquals(1, OverviewGrid.move(KeyCode.RIGHT, 0, 5, 2));
        assertEquals(0, OverviewGrid.move(KeyCode.LEFT, 0, 5, 2));
        assertEquals(2, OverviewGrid.move(KeyCode.DOWN, 0, 5, 2));
        assertEquals(4, OverviewGrid.move(KeyCode.DOWN, 3, 5, 2));
        assertEquals(0, OverviewGrid.move(KeyCode.UP, 1, 5, 2));
        assertEquals(-2, OverviewGrid.move(KeyCode.A, 1, 5, 2));
    }

    @Test
    void metaSkipsBlanksAndRepeatedName() {
        var card = new OverviewGrid.Card("app", "app", "work", "ctx 34k / 200k · 17%", false, false, null, List.of());
        assertEquals("work · ctx 17%", OverviewGrid.meta(card));
        var bare = new OverviewGrid.Card("a", "proj", "", null, false, false, null, List.of());
        assertEquals("proj", OverviewGrid.meta(bare));
    }
}
