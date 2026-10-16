package dev.kamil.fabric.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javafx.geometry.Orientation;
import org.junit.jupiter.api.Test;

class SplitOrientationTest {

    @Test
    void togglesBetweenHorizontalAndVertical() {
        assertEquals(Orientation.VERTICAL, TerminalTab.toggled(Orientation.HORIZONTAL));
        assertEquals(Orientation.HORIZONTAL, TerminalTab.toggled(Orientation.VERTICAL));
    }
}
