package dev.kamil.fabric.water;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the water-reminder files (config and log.csv in ~/.config/water-reminder). The in-app
 * WaterReminder shows the reminders; this class never does. NOTIFY and POPUP are kept for compatibility only.
 */
public final class WaterStore {

    public record Config(int ml, int everyMin, int goalMl, boolean notification, boolean popup,
            boolean enabled) {
        public static final Config DEFAULT = new Config(250, 60, 2000, true, true, false);
    }

    public record Entry(LocalDate date, String time, int ml, String status) {
    }

    public static final String DRANK = "drank";
    public static final String SKIPPED = "skipped";
    public static final String MISSED = "missed";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final Path dir;

    public WaterStore(Path dir) {
        this.dir = dir;
    }

    /** The script's directory, ~/.config/water-reminder. */
    public static Path standardDir() {
        return Path.of(System.getProperty("user.home"), ".config", "water-reminder");
    }

    private Path configFile() {
        return dir.resolve("config");
    }

    private Path logFile() {
        return dir.resolve("log.csv");
    }

    /** Creates the directory, config (defaults) and log when missing, when missing. */
    public void ensure() throws IOException {
        Files.createDirectories(dir);
        if (!Files.exists(configFile())) {
            writeConfig(Config.DEFAULT);
        }
        if (!Files.exists(logFile())) {
            Files.createFile(logFile());
        }
    }

    public Config readConfig() throws IOException {
        ensure();
        int ml = Config.DEFAULT.ml();
        int every = Config.DEFAULT.everyMin();
        int goal = Config.DEFAULT.goalMl();
        boolean notification = true;
        boolean popup = true;
        boolean enabled = Config.DEFAULT.enabled();
        for (var line : Files.readAllLines(configFile(), StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            var key = line.substring(0, eq).strip();
            var value = line.substring(eq + 1).strip();
            try {
                switch (key) {
                    case "ML" -> ml = Integer.parseInt(value);
                    case "EVERY_MIN" -> every = Integer.parseInt(value);
                    case "GOAL_ML" -> goal = Integer.parseInt(value);
                    case "NOTIFY" -> notification = !value.equals("0");
                    case "POPUP" -> popup = !value.equals("0");
                    case "ENABLED" -> enabled = !value.equals("0");
                    default -> { }
                }
            } catch (NumberFormatException e) {
                // keep the default for an unreadable value
            }
        }
        return new Config(ml, every, goal, notification, popup, enabled);
    }

    public void writeConfig(Config config) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(configFile(),
                "ML=" + config.ml() + "\nEVERY_MIN=" + config.everyMin() + "\nGOAL_ML=" + config.goalMl()
                        + "\nNOTIFY=" + (config.notification() ? 1 : 0) + "\nPOPUP=" + (config.popup() ? 1 : 0)
                        + "\nENABLED=" + (config.enabled() ? 1 : 0) + "\n",
                StandardCharsets.UTF_8);
    }

    /** All well-formed log lines, oldest first. */
    public List<Entry> readLog() throws IOException {
        ensure();
        var entries = new ArrayList<Entry>();
        for (var line : Files.readAllLines(logFile(), StandardCharsets.UTF_8)) {
            var parts = line.strip().split(",");
            if (parts.length < 4) {
                continue;
            }
            try {
                entries.add(new Entry(LocalDate.parse(parts[0]), parts[1], Integer.parseInt(parts[2]), parts[3]));
            } catch (RuntimeException e) {
                // skip a malformed line
            }
        }
        return entries;
    }

    public List<Entry> today(LocalDate today) throws IOException {
        return readLog().stream().filter(e -> e.date().equals(today)).toList();
    }

    /** Sum of ml drunk on the day; other days and other statuses are ignored. */
    public static int total(List<Entry> entries, LocalDate day) {
        return entries.stream().filter(e -> e.date().equals(day) && DRANK.equals(e.status()))
                .mapToInt(Entry::ml).sum();
    }

    public static long count(List<Entry> entries, LocalDate day, String status) {
        return entries.stream().filter(e -> e.date().equals(day) && status.equals(e.status())).count();
    }

    /** The log line format: date,HH:mm,ml,status. */
    public static String format(LocalDate date, LocalTime time, int ml, String status) {
        return date + "," + TIME.format(time) + "," + ml + "," + status;
    }

    public void append(LocalDate date, LocalTime time, int ml, String status) throws IOException {
        ensure();
        Files.writeString(logFile(), format(date, time, ml, status) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
    }
}
