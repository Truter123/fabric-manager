package dev.kamil.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The F1 sheet, the key bindings and the README table all come from {@link Shortcut}. */
class ShortcutTest {

    @Test
    void everyShortcutHasADistinctKeyAndComboAndADescription() {
        var keys = new HashSet<String>();
        var combos = new HashSet<Object>();
        for (var shortcut : Shortcut.values()) {
            assertTrue(keys.add(shortcut.keys()), "duplicate key text " + shortcut.keys());
            assertTrue(combos.add(shortcut.combo()), "duplicate combination " + shortcut.keys());
            assertFalse(shortcut.what().isBlank(), shortcut + " has no description");
        }
    }

    @Test
    void sheetTextMatchesTheCombination() {
        assertEquals("Ctrl+Shift+N", Shortcut.NEW.keys());
        assertEquals("Ctrl+Shift+,", Shortcut.SETTINGS.keys());
        assertEquals("Ctrl+Shift+Tab", Shortcut.PREVIOUS_TAB.keys());
        assertEquals("Ctrl+Tab", Shortcut.NEXT_TAB.keys());
        assertEquals("F1", Shortcut.KEYS.keys());
        assertEquals("Ctrl+Shift+N  new session", Shortcut.NEW.tooltip());
    }

    @Test
    void theAppBindsEveryShortcutAndOnlyShortcuts() throws IOException {
        var source = Files.readString(Path.of("src/main/java/dev/kamil/fabric/FabricManagerApp.java"));
        for (var shortcut : Shortcut.values()) {
            assertTrue(source.contains("Shortcut." + shortcut.name() + "."), shortcut + " is not bound in FabricManagerApp");
        }
        assertFalse(source.contains("new KeyCodeCombination"), "a key bound outside Shortcut would miss the F1 sheet");
    }

    @Test
    void readmeTableListsEveryShortcut() throws IOException {
        var listed = new LinkedHashSet<String>();
        for (var line : Files.readAllLines(Path.of("README.md"))) {
            if (line.startsWith("| ")) {
                var cell = line.split("\\|")[1].trim();
                listed.addAll(Arrays.asList(cell.split(" / ")));
            }
        }
        Set<String> missing = new LinkedHashSet<>();
        for (var shortcut : Shortcut.values()) {
            if (!listed.contains(shortcut.keys())) {
                missing.add(shortcut.keys());
            }
        }
        assertTrue(missing.isEmpty(), "README key table misses " + missing);
    }
}
