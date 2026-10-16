package dev.kamil.fabric.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WheelScrollTest {

    @Test
    void wheelNotchUpMovesThreeLinesUp() {
        assertEquals(-3, new WheelScroll().lines(40));
    }

    @Test
    void wheelNotchDownMovesThreeLinesDown() {
        assertEquals(3, new WheelScroll().lines(-40));
    }

    @Test
    void smallTouchpadDeltasAddUp() {
        var wheel = new WheelScroll();
        int total = 0;
        for (int i = 0; i < 8; i++) {
            total += wheel.lines(5);
        }
        assertEquals(-3, total);
    }

    @Test
    void directionChangeKeepsNoStaleCarry() {
        var wheel = new WheelScroll();
        assertEquals(0, wheel.lines(10));
        assertEquals(0, wheel.lines(-10));
        assertEquals(-3, wheel.lines(40));
    }
}
