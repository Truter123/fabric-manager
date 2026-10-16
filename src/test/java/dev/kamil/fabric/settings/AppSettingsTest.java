package dev.kamil.fabric.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.session.Database;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppSettingsTest {

    @TempDir
    Path tmp;

    Database db;

    @BeforeEach
    void setUp() {
        db = Database.at(tmp.resolve("db/fabric")).migrate();
    }

    @Test
    void defaultsWhenNothingIsStored() {
        var settings = new AppSettings(db);
        assertEquals("x", settings.getString("a", "x"));
        assertTrue(settings.getBoolean("b", true));
        assertEquals(14, settings.getInt("c", 14));
    }

    @Test
    void valuesSurviveAReload() {
        var settings = new AppSettings(db);
        settings.setInt("terminal.fontSize", 18);
        settings.setBoolean("notify.sound", false);
        settings.setString("account.work.label", "Team");
        settings.setString("account.work.label", "Team 2");

        var reloaded = new AppSettings(db);
        assertEquals(18, reloaded.getInt("terminal.fontSize", 14));
        assertFalse(reloaded.getBoolean("notify.sound", true));
        assertEquals("Team 2", reloaded.getString("account.work.label", ""));
    }

    @Test
    void removeGoesBackToTheDefaultAlsoAfterReload() {
        var settings = new AppSettings(db);
        settings.setInt("n", 5);
        settings.remove("n");
        assertEquals(1, settings.getInt("n", 1));
        assertEquals(1, new AppSettings(db).getInt("n", 1));
    }

    @Test
    void notANumberFallsBackToTheDefault() {
        var settings = AppSettings.inMemory();
        settings.setString("n", "abc");
        assertEquals(7, settings.getInt("n", 7));
    }

    @Test
    void listenerGetsTheKeyOnlyOnChange() {
        var settings = AppSettings.inMemory();
        var keys = new ArrayList<String>();
        settings.addListener(keys::add);
        settings.setInt("n", 1);
        settings.setInt("n", 1);
        settings.setInt("n", 2);
        settings.remove("n");
        settings.remove("n");
        assertEquals(List.of("n", "n", "n"), keys);
    }
}
