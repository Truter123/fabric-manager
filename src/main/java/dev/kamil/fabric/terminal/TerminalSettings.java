package dev.kamil.fabric.terminal;

import com.techsenger.jeditermfx.core.TerminalColor;
import com.techsenger.jeditermfx.core.TextStyle;
import com.techsenger.jeditermfx.ui.settings.DefaultSettingsProvider;
import dev.kamil.fabric.settings.AppSettings;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.text.Font;

/**
 * Dark theme, a real monospace font and a long scrollback for Claude Code sessions. Font, size, scrollback, copy on
 * select and the bell come from {@link AppSettings}; they are read when a tab is created.
 */
public final class TerminalSettings extends DefaultSettingsProvider {

    public static final String FONT_FAMILY = "terminal.fontFamily";
    public static final String FONT_SIZE_KEY = "terminal.fontSize";
    public static final String SCROLLBACK = "terminal.scrollback";
    public static final String COPY_ON_SELECT = "terminal.copyOnSelect";
    public static final String AUDIBLE_BELL = "terminal.audibleBell";
    public static final int DEFAULT_SCROLLBACK = 5_000;

    private static final String[] FONTS = {"JetBrains Mono", "Fira Code", "DejaVu Sans Mono", "Monospaced"};
    private static final int FONT_SIZE = 14;
    private static final TerminalColor FOREGROUND = TerminalColor.rgb(0xd8, 0xdb, 0xe2);
    private static final TerminalColor BACKGROUND = TerminalColor.rgb(0x11, 0x13, 0x18);
    private static final TerminalColor ACCENT = TerminalColor.rgb(0x7a, 0xa2, 0xf7);
    private static final TerminalColor SELECTION = TerminalColor.rgb(0x33, 0x46, 0x7c);
    private static final TerminalColor MATCH = TerminalColor.rgb(0xe0, 0xaf, 0x68);

    private final AppSettings settings;
    private Font font;

    public TerminalSettings() {
        this(AppSettings.shared());
    }

    public TerminalSettings(AppSettings settings) {
        this.settings = settings;
    }

    /** The preferred families that are installed, then other installed monospace-looking families. Needs JavaFX. */
    public static List<String> fontChoices() {
        var installed = Font.getFamilies();
        var result = new ArrayList<String>();
        for (var family : FONTS) {
            if (installed.contains(family)) {
                result.add(family);
            }
        }
        for (var family : installed) {
            var lower = family.toLowerCase(java.util.Locale.ROOT);
            if (!result.contains(family) && (lower.contains("mono") || lower.contains("code")
                    || lower.contains("courier"))) {
                result.add(family);
            }
        }
        return result;
    }

    @Override
    public Font getTerminalFont() {
        if (font == null) {
            font = pickFont(settings.getString(FONT_FAMILY, ""), size());
        }
        return font;
    }

    @Override
    public float getTerminalFontSize() {
        return size();
    }

    private float size() {
        return Math.max(6, Math.min(72, settings.getInt(FONT_SIZE_KEY, FONT_SIZE)));
    }

    @Override
    public TerminalColor getDefaultForeground() {
        return FOREGROUND;
    }

    @Override
    public TerminalColor getDefaultBackground() {
        return BACKGROUND;
    }

    /**
     * The widget seeds its style state from this deprecated method, not from the two above. The inherited
     * black-on-white style is applied on every cursor restore (ESC 8) and painted white cells under Claude's text.
     */
    @Override
    @SuppressWarnings("deprecation")
    public TextStyle getDefaultStyle() {
        return new TextStyle(FOREGROUND, BACKGROUND);
    }

    /** Shown on hover; the inherited blue-on-white is a light box in a dark terminal. */
    @Override
    public TextStyle getHyperlinkColor() {
        return new TextStyle(ACCENT, BACKGROUND);
    }

    /**
     * Off: the inherited inverse selection swaps text and background, a light grey block in a dark terminal. Only
     * when it is off does the widget use {@link #getSelectionColor()}.
     */
    @Override
    public boolean useInverseSelectionColor() {
        return false;
    }

    /** Dark blue under the normal text colour, instead of the inherited white on bright blue. */
    @Override
    public TextStyle getSelectionColor() {
        return new TextStyle(FOREGROUND, SELECTION);
    }

    /** Find (Ctrl+F) matches: the inherited black on pure yellow, toned down to the app's amber. */
    @Override
    public TextStyle getFoundPatternColor() {
        return new TextStyle(BACKGROUND, MATCH);
    }

    /** The widget runs a repaint timeline per tab for its whole life; 30 FPS is plenty for a text terminal. */
    @Override
    public int maxRefreshRate() {
        return 30;
    }

    @Override
    public int getBufferMaxLinesCount() {
        return Math.max(100, settings.getInt(SCROLLBACK, DEFAULT_SCROLLBACK));
    }

    /**
     * On: the bell (Claude sends BEL when it finishes) plays a sound through canberra-gtk-play. Off by default:
     * the sidebar dot already shows the state.
     */
    @Override
    public boolean audibleBell() {
        return settings.getBoolean(AUDIBLE_BELL, false);
    }

    @Override
    public boolean copyOnSelect() {
        return settings.getBoolean(COPY_ON_SELECT, false);
    }

    private static Font pickFont(String chosen, float size) {
        var installed = Font.getFamilies();
        if (!chosen.isBlank() && installed.contains(chosen)) {
            return Font.font(chosen, size);
        }
        for (var family : FONTS) {
            if (installed.contains(family)) {
                return Font.font(family, size);
            }
        }
        return Font.font("Monospaced", size);
    }
}
