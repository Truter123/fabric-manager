package dev.kamil.fabric.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WaterStoreTest {

    @TempDir
    Path tmp;

    @Test
    void parsesConfig() throws IOException {
        Files.writeString(tmp.resolve("config"), "ML=300\nEVERY_MIN=45\nGOAL_ML=2500\nNOTIFY=0\nPOPUP=1\n");
        assertEquals(new WaterStore.Config(300, 45, 2500, false, true), new WaterStore(tmp).readConfig());
    }

    @Test
    void oldThreeLineConfigDefaultsNotifyAndPopupOn() throws IOException {
        Files.writeString(tmp.resolve("config"), "ML=300\nEVERY_MIN=45\nGOAL_ML=2500\n");
        assertEquals(new WaterStore.Config(300, 45, 2500, true, true), new WaterStore(tmp).readConfig());
    }

    @Test
    void missingFilesGetDefaults() throws IOException {
        var dir = tmp.resolve("new");
        var store = new WaterStore(dir);
        assertEquals(WaterStore.Config.DEFAULT, store.readConfig());
        assertEquals("ML=250\nEVERY_MIN=60\nGOAL_ML=2000\nNOTIFY=1\nPOPUP=1\n", Files.readString(dir.resolve("config")));
        assertTrue(Files.exists(dir.resolve("log.csv")));
    }

    @Test
    void writesConfigInTheScriptFormat() throws IOException {
        new WaterStore(tmp).writeConfig(new WaterStore.Config(200, 30, 1800, true, false));
        assertEquals("ML=200\nEVERY_MIN=30\nGOAL_ML=1800\nNOTIFY=1\nPOPUP=0\n", Files.readString(tmp.resolve("config")));
    }

    @Test
    void todayTotalIgnoresOtherDaysAndNonDrank() throws IOException {
        Files.writeString(tmp.resolve("log.csv"), """
                2026-10-15,10:00,250,drank
                2026-10-16,09:00,250,drank
                2026-10-16,10:00,250,skipped
                2026-10-16,11:00,250,missed
                2026-10-16,12:00,300,drank
                garbage
                """);
        var store = new WaterStore(tmp);
        var day = LocalDate.of(2026, 10, 16);
        var log = store.readLog();
        assertEquals(550, WaterStore.total(log, day));
        assertEquals(2, WaterStore.count(log, day, WaterStore.DRANK));
        assertEquals(1, WaterStore.count(log, day, WaterStore.SKIPPED));
        assertEquals(1, WaterStore.count(log, day, WaterStore.MISSED));
        assertEquals(4, store.today(day).size());
    }

    @Test
    void appendsOneLineInTheScriptFormat() throws IOException {
        var store = new WaterStore(tmp);
        store.append(LocalDate.of(2026, 10, 16), LocalTime.of(9, 5), 250, WaterStore.DRANK);
        store.append(LocalDate.of(2026, 10, 16), LocalTime.of(10, 0), 250, WaterStore.SKIPPED);
        assertEquals("2026-10-16,09:05,250,drank\n2026-10-16,10:00,250,skipped\n",
                Files.readString(tmp.resolve("log.csv")));
        assertEquals("2026-10-16,09:05,250,drank", WaterStore.format(LocalDate.of(2026, 10, 16), LocalTime.of(9, 5), 250, "drank"));
    }
}
