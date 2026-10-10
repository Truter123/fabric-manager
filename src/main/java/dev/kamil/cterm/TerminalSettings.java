package dev.kamil.cterm;

import com.techsenger.jeditermfx.core.TerminalColor;
import com.techsenger.jeditermfx.ui.settings.DefaultSettingsProvider;
import javafx.scene.text.Font;

/** Dark theme, a real monospace font and a long scrollback for Claude Code sessions. */
final class TerminalSettings extends DefaultSettingsProvider {

    private static final String[] FONTS = {"JetBrains Mono", "Fira Code", "DejaVu Sans Mono", "Monospaced"};
    private static final float FONT_SIZE = 14;

    private final Font font = pickFont();

    @Override
    public Font getTerminalFont() {
        return font;
    }

    @Override
    public float getTerminalFontSize() {
        return FONT_SIZE;
    }

    @Override
    public TerminalColor getDefaultForeground() {
        return TerminalColor.rgb(0xd4, 0xd4, 0xd4);
    }

    @Override
    public TerminalColor getDefaultBackground() {
        return TerminalColor.rgb(0x1e, 0x1e, 0x1e);
    }

    @Override
    public int getBufferMaxLinesCount() {
        return 20_000;
    }

    @Override
    public boolean copyOnSelect() {
        return false;
    }

    private static Font pickFont() {
        var installed = Font.getFamilies();
        for (var family : FONTS) {
            if (installed.contains(family)) {
                return Font.font(family, FONT_SIZE);
            }
        }
        return Font.font("Monospaced", FONT_SIZE);
    }
}
