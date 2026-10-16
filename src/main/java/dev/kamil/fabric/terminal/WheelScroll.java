package dev.kamil.fabric.terminal;

/**
 * Turns wheel and touchpad deltas into scrollback lines.
 *
 * <p>JediTermFX rounds {@code deltaY / 40} per event: a mouse notch moves one line and the small deltas of a touchpad
 * round to zero, so the touchpad does not scroll at all. Here the fractions are summed between events instead.
 */
final class WheelScroll {

    /** JavaFX reports one wheel notch as 40 pixels. */
    private static final double PIXELS_PER_NOTCH = 40;
    private static final double LINES_PER_NOTCH = 3;

    private double carry;

    /** Lines to move for this event: negative = up into the scrollback, positive = down. */
    int lines(double deltaY) {
        carry -= deltaY / PIXELS_PER_NOTCH * LINES_PER_NOTCH;
        int whole = (int) carry;
        carry -= whole;
        return whole;
    }
}
