package dev.kamil.fabric.processes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/** Reads CPU, GPU and NVMe temperature, CPU load, RAM and battery from sysfs and procfs. Never throws. */
public class SystemStats {

    /** One displayed part; level is "" (normal), "warn" or "hot". */
    public record Part(String text, String level) {
    }

    private static final int WARN_CELSIUS = 80;
    private static final int HOT_CELSIUS = 90;
    private static final int RAM_WARN_PERCENT = 90;

    private final Path sys;
    private final Path proc;
    private long[] lastCpu;

    public SystemStats(Path sys, Path proc) {
        this.sys = sys;
        this.proc = proc;
    }

    public static SystemStats system() {
        return new SystemStats(Path.of("/sys"), Path.of("/proc"));
    }

    /** Reads the sensors now; CPU percent needs a previous call and is "–" on the first one. */
    public List<Part> sample() {
        var parts = new ArrayList<Part>();
        var cpuTemp = cpuTemperature();
        var cpuPercent = cpuPercent();
        if (cpuTemp.isPresent() || cpuPercent.isPresent() || lastCpu != null) {
            var text = new StringBuilder("CPU");
            cpuTemp.ifPresent(t -> text.append(' ').append(t).append("°C"));
            text.append("  ").append(cpuPercent.map(p -> p + "%").orElse("–"));
            parts.add(new Part(text.toString(), level(cpuTemp)));
        }
        hwmonTemperature("amdgpu").ifPresent(t -> parts.add(new Part("GPU " + t + "°C", level(Optional.of(t)))));
        hwmonTemperature("nvme").ifPresent(t -> parts.add(new Part("NVMe " + t + "°C", level(Optional.of(t)))));
        ram().ifPresent(parts::add);
        battery().ifPresent(parts::add);
        return parts;
    }

    /** All parts on one line, separated by four spaces. */
    public static String format(List<Part> parts) {
        return String.join("    ", parts.stream().map(Part::text).toList());
    }

    private static String level(Optional<Integer> celsius) {
        int t = celsius.orElse(0);
        return t >= HOT_CELSIUS ? "hot" : t >= WARN_CELSIUS ? "warn" : "";
    }

    private Optional<Integer> cpuTemperature() {
        for (var name : List.of("k10temp", "coretemp")) {
            var t = hwmonTemperature(name);
            if (t.isPresent()) {
                return t;
            }
        }
        var zones = sys.resolve("class/thermal");
        try (Stream<Path> dirs = Files.list(zones)) {
            for (var zone : dirs.filter(p -> p.getFileName().toString().startsWith("thermal_zone")).sorted().toList()) {
                var type = read(zone.resolve("type"));
                if (type.isPresent() && (type.get().equals("acpitz") || type.get().equals("x86_pkg_temp"))) {
                    var t = milliToCelsius(read(zone.resolve("temp")));
                    if (t.isPresent()) {
                        return t;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private Optional<Integer> hwmonTemperature(String name) {
        var root = sys.resolve("class/hwmon");
        try (Stream<Path> dirs = Files.list(root)) {
            for (var dir : dirs.sorted().toList()) {
                if (read(dir.resolve("name")).filter(name::equals).isPresent()) {
                    var t = milliToCelsius(read(dir.resolve("temp1_input")));
                    if (t.isPresent()) {
                        return t;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<Integer> milliToCelsius(Optional<String> raw) {
        try {
            return raw.map(s -> (int) Math.round(Long.parseLong(s) / 1000.0));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private Optional<Integer> cpuPercent() {
        var line = read(proc.resolve("stat")).flatMap(s -> s.lines().findFirst());
        if (line.isEmpty() || !line.get().startsWith("cpu")) {
            return Optional.empty();
        }
        try {
            var fields = line.get().trim().split("\\s+");
            long total = 0;
            for (int i = 1; i < fields.length; i++) {
                total += Long.parseLong(fields[i]);
            }
            long idle = Long.parseLong(fields[4]) + (fields.length > 5 ? Long.parseLong(fields[5]) : 0);
            var previous = lastCpu;
            lastCpu = new long[] {total - idle, total};
            if (previous == null || lastCpu[1] <= previous[1]) {
                return Optional.empty();
            }
            double busy = lastCpu[0] - previous[0];
            double all = lastCpu[1] - previous[1];
            return Optional.of((int) Math.round(Math.max(0, Math.min(100, busy * 100 / all))));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private Optional<Part> ram() {
        var text = read(proc.resolve("meminfo"));
        if (text.isEmpty()) {
            return Optional.empty();
        }
        long total = -1;
        long available = -1;
        for (var line : text.get().split("\n")) {
            if (line.startsWith("MemTotal:")) {
                total = kilobytes(line);
            } else if (line.startsWith("MemAvailable:")) {
                available = kilobytes(line);
            }
        }
        if (total <= 0 || available < 0) {
            return Optional.empty();
        }
        double used = total - available;
        int percent = (int) Math.round(used * 100 / total);
        var label = String.format(Locale.ROOT, "RAM %.1f / %.0f GB (%d%%)",
                used / 1024 / 1024, total / 1024.0 / 1024, percent);
        return Optional.of(new Part(label, percent >= RAM_WARN_PERCENT ? "warn" : ""));
    }

    private static long kilobytes(String line) {
        try {
            return Long.parseLong(line.substring(line.indexOf(':') + 1).replace("kB", "").trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private Optional<Part> battery() {
        var root = sys.resolve("class/power_supply");
        try (Stream<Path> dirs = Files.list(root)) {
            for (var dir : dirs.filter(p -> p.getFileName().toString().startsWith("BAT")).sorted().toList()) {
                var capacity = read(dir.resolve("capacity"));
                if (capacity.isEmpty()) {
                    continue;
                }
                int percent = Integer.parseInt(capacity.get());
                var status = read(dir.resolve("status")).orElse("");
                var suffix = switch (status) {
                    case "Charging" -> " ⚡";
                    case "Full" -> " full";
                    default -> "";
                };
                return Optional.of(new Part("BAT " + percent + "%" + suffix, ""));
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<String> read(Path file) {
        try {
            return Optional.of(Files.readString(file).strip());
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
