package dev.kamil.fabric.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.techsenger.jeditermfx.core.TerminalColor;

import org.junit.jupiter.api.Test;

class TerminalSettingsTest {

    /** The widget seeds its style state from getDefaultStyle(); black on white there painted white cells. */
    @Test
    @SuppressWarnings("deprecation")
    void defaultStyleMatchesTheDarkTheme() {
        var settings = new TerminalSettings();
        assertEquals(settings.getDefaultBackground(), settings.getDefaultStyle().getBackground());
        assertEquals(settings.getDefaultForeground(), settings.getDefaultStyle().getForeground());
    }

    @Test
    void hoveredLinkKeepsTheDarkBackground() {
        var settings = new TerminalSettings();
        assertEquals(settings.getDefaultBackground(), settings.getHyperlinkColor().getBackground());
    }

    @Test
    void selectionIsADarkBackgroundUnderTheNormalText() {
        var settings = new TerminalSettings();
        // With inverse selection on, the widget ignores getSelectionColor() and paints a light block.
        assertFalse(settings.useInverseSelectionColor());
        var selection = settings.getSelectionColor();
        assertEquals(settings.getDefaultForeground(), selection.getForeground());
        assertTrue(luminance(selection.getBackground()) < 0.1, "selection too bright");
    }

    @Test
    void findMatchIsNotPureYellow() {
        var match = new TerminalSettings().getFoundPatternColor().getBackground();
        assertTrue(luminance(match) < luminance(TerminalColor.rgb(255, 255, 0)) * 0.6, "match too bright");
    }

    /** Relative luminance (sRGB, WCAG): 0 black, 1 white. */
    private static double luminance(TerminalColor color) {
        var c = color.toColor();
        return 0.2126 * linear(c.getRed()) + 0.7152 * linear(c.getGreen()) + 0.0722 * linear(c.getBlue());
    }

    private static double linear(int channel) {
        double v = channel / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }
}
