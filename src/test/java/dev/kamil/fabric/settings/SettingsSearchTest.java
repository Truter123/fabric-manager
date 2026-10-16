package dev.kamil.fabric.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.settings.SettingsSearch.Entry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SettingsSearchTest {

    private static final Entry FONT = new Entry("Terminal", "Font size", "Applies to new tabs");
    private static final Entry SOUND = new Entry("Notifications", "Sound", "Play a sound when a session needs you");

    @Test
    void blankQueryMatchesEverything() {
        assertTrue(SettingsSearch.matches(FONT, ""));
        assertTrue(SettingsSearch.matches(FONT, "  "));
    }

    @Test
    void matchesLabelAndHelpIgnoringCase() {
        assertTrue(SettingsSearch.matches(FONT, "FONT"));
        assertTrue(SettingsSearch.matches(FONT, "new tabs"));
        assertFalse(SettingsSearch.matches(FONT, "sound"));
    }

    @Test
    void everyWordMustMatch() {
        assertTrue(SettingsSearch.matches(SOUND, "sound session"));
        assertFalse(SettingsSearch.matches(SOUND, "sound tabs"));
    }

    @Test
    void groupsWithAMatchInOrder() {
        assertEquals(Set.of("Notifications"), SettingsSearch.groupsMatching(List.of(FONT, SOUND), "sound"));
        assertEquals(List.of("Terminal", "Notifications"),
                List.copyOf(SettingsSearch.groupsMatching(List.of(FONT, SOUND), "")));
    }
}
