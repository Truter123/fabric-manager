package dev.kamil.fabric.processes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemStatsTest {

    @TempDir
    Path sys;
    @TempDir
    Path proc;

    private void hwmon(String dir, String name, String milli) throws IOException {
        var d = sys.resolve("class/hwmon").resolve(dir);
        Files.createDirectories(d);
        Files.writeString(d.resolve("name"), name + "\n");
        Files.writeString(d.resolve("temp1_input"), milli + "\n");
    }

    private void stat(String line) throws IOException {
        Files.writeString(proc.resolve("stat"), line + "\ncpu0 1 1 1 1\n");
    }

    private void meminfo(long total, long available) throws IOException {
        Files.writeString(proc.resolve("meminfo"),
                "MemTotal:       " + total + " kB\nMemFree:  5 kB\nMemAvailable:   " + available + " kB\n");
    }

    private void battery(String capacity, String status) throws IOException {
        var d = sys.resolve("class/power_supply/BAT0");
        Files.createDirectories(d);
        Files.writeString(d.resolve("capacity"), capacity + "\n");
        Files.writeString(d.resolve("status"), status + "\n");
    }

    @Test
    void findsSensorsByNameRegardlessOfHwmonNumber() throws IOException {
        hwmon("hwmon7", "k10temp", "62000");
        hwmon("hwmon2", "amdgpu", "48000");
        hwmon("hwmon0", "nvme", "41000");
        var line = SystemStats.format(new SystemStats(sys, proc).sample());
        assertTrue(line.contains("CPU 62°C"), line);
        assertTrue(line.contains("GPU 48°C"), line);
        assertTrue(line.contains("NVMe 41°C"), line);
    }

    @Test
    void missingSensorsAreOmitted() throws IOException {
        hwmon("hwmon1", "k10temp", "50000");
        var line = SystemStats.format(new SystemStats(sys, proc).sample());
        assertEquals("CPU 50°C  –", line);
    }

    @Test
    void nothingAvailableGivesNoParts() {
        assertTrue(new SystemStats(sys, proc).sample().isEmpty());
    }

    @Test
    void cpuFallsBackToCoretempThenThermalZone() throws IOException {
        hwmon("hwmon3", "coretemp", "55000");
        assertTrue(SystemStats.format(new SystemStats(sys, proc).sample()).contains("CPU 55°C"));

        var other = Files.createTempDirectory(sys, "other");
        var zone = other.resolve("class/thermal/thermal_zone0");
        Files.createDirectories(zone);
        Files.writeString(zone.resolve("type"), "acpitz\n");
        Files.writeString(zone.resolve("temp"), "44000\n");
        assertTrue(SystemStats.format(new SystemStats(other, proc).sample()).contains("CPU 44°C"));
    }

    @Test
    void cpuPercentFromTwoSamples() throws IOException {
        hwmon("hwmon1", "k10temp", "50000");
        var stats = new SystemStats(sys, proc);
        stat("cpu  100 0 100 800 0 0 0 0 0 0");
        assertEquals("CPU 50°C  –", SystemStats.format(stats.sample()));
        stat("cpu  130 0 130 840 0 0 0 0 0 0");
        // delta: busy 60, total 100
        assertEquals("CPU 50°C  60%", SystemStats.format(stats.sample()));
    }

    @Test
    void parsesMeminfo() throws IOException {
        meminfo(32L * 1024 * 1024, 21_810_381);
        var parts = new SystemStats(sys, proc).sample();
        assertEquals(1, parts.size());
        assertEquals("RAM 11.2 / 32 GB (35%)", parts.get(0).text());
        assertEquals("", parts.get(0).level());
    }

    @Test
    void highRamIsWarning() throws IOException {
        meminfo(1_000_000, 50_000);
        assertEquals("warn", new SystemStats(sys, proc).sample().get(0).level());
    }

    @Test
    void batteryStates() throws IOException {
        battery("84", "Charging");
        assertEquals("BAT 84% ⚡", new SystemStats(sys, proc).sample().get(0).text());
        battery("100", "Full");
        assertEquals("BAT 100% full", new SystemStats(sys, proc).sample().get(0).text());
        battery("70", "Discharging");
        assertEquals("BAT 70%", new SystemStats(sys, proc).sample().get(0).text());
    }

    @Test
    void temperatureLevels() throws IOException {
        hwmon("hwmon1", "k10temp", "85000");
        hwmon("hwmon2", "amdgpu", "95000");
        hwmon("hwmon3", "nvme", "40000");
        var parts = new SystemStats(sys, proc).sample();
        assertEquals("warn", parts.get(0).level());
        assertEquals("hot", parts.get(1).level());
        assertEquals("", parts.get(2).level());
    }
}
