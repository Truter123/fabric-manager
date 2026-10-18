package dev.kamil.fabric.processes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Finds Java and Node processes through /proc and {@code ss}. Blocks; call off the UI thread. */
public class DevProcesses {

    private static final Pattern SS_PID = Pattern.compile("pid=(\\d+)");
    private static final Pattern SS_PORT = Pattern.compile(":(\\d+)$");
    private static final Set<String> JAVA_VALUE_OPTIONS =
            Set.of("-cp", "-classpath", "--class-path", "-p", "--module-path");

    public List<DevProcess> list() {
        var ports = parsePorts(ssLines());
        var self = ProcessHandle.current().pid();
        var user = System.getProperty("user.name");
        var result = new ArrayList<DevProcess>();
        ProcessHandle.allProcesses().forEach(handle -> {
            long pid = handle.pid();
            if (pid == self) {
                return;
            }
            try {
                var argv = cmdline(pid);
                if (argv.isEmpty()) {
                    return;
                }
                var exe = argv.get(0);
                var base = exe.substring(exe.lastIndexOf('/') + 1);
                DevProcess.Kind kind = switch (base) {
                    case "java" -> DevProcess.Kind.JAVA;
                    case "node" -> DevProcess.Kind.NODE;
                    default -> null;
                };
                if (kind == null) {
                    return;
                }
                boolean container = inContainer(pid);
                boolean mine = handle.info().user().map(user::equals).orElse(false);
                if (!mine && !container) {
                    return;
                }
                var name = kind == DevProcess.Kind.JAVA ? nameFor(argv) : nodeNameFor(argv);
                result.add(new DevProcess(pid, kind, name, cwd(pid), ports.getOrDefault(pid, List.of()),
                        container, String.join(" ", argv)));
            } catch (RuntimeException ignored) {
                // process vanished or unreadable
            }
        });
        result.sort(Comparator.comparingLong(DevProcess::pid));
        return result;
    }

    /** Friendly name of a java or node command line; argv[0] is the executable. */
    public static String nameFor(List<String> argv) {
        if (argv.isEmpty()) {
            return "";
        }
        var exe = argv.get(0);
        var base = exe.substring(exe.lastIndexOf('/') + 1);
        return base.equals("node") ? nodeNameFor(argv) : javaNameFor(argv);
    }

    private static String javaNameFor(List<String> argv) {
        for (int i = 1; i < argv.size(); i++) {
            var arg = argv.get(i);
            if (arg.equals("-jar")) {
                return i + 1 < argv.size() ? baseName(argv.get(i + 1)) : arg;
            }
            if (arg.equals("-m") || arg.equals("--module")) {
                if (i + 1 >= argv.size()) {
                    return arg;
                }
                var module = argv.get(i + 1);
                int slash = module.indexOf('/');
                return slash >= 0 ? module.substring(slash + 1) : module;
            }
            if (arg.startsWith("--module=")) {
                var module = arg.substring("--module=".length());
                int slash = module.indexOf('/');
                return slash >= 0 ? module.substring(slash + 1) : module;
            }
            if (JAVA_VALUE_OPTIONS.contains(arg)) {
                i++;
                continue;
            }
            if (arg.startsWith("-")) {
                continue;
            }
            return arg;
        }
        return "java";
    }

    private static String nodeNameFor(List<String> argv) {
        int script = -1;
        for (int i = 1; i < argv.size(); i++) {
            if (!argv.get(i).startsWith("-")) {
                script = i;
                break;
            }
        }
        if (script < 0) {
            return "node";
        }
        var path = argv.get(script);
        if (path.contains("@angular/cli") || path.endsWith("/ng") || path.equals("ng")) {
            var rest = argv.subList(script + 1, argv.size());
            return rest.isEmpty() ? "ng" : "ng " + String.join(" ", rest);
        }
        return baseName(path);
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Maps pid to its listening TCP ports from {@code ss -ltnpH} lines. */
    public static Map<Long, List<Integer>> parsePorts(List<String> ssLines) {
        var sets = new HashMap<Long, TreeSet<Integer>>();
        for (var line : ssLines) {
            var cols = line.trim().split("\\s+");
            if (cols.length < 4) {
                continue;
            }
            var portMatch = SS_PORT.matcher(cols[3]);
            if (!portMatch.find()) {
                continue;
            }
            int port = Integer.parseInt(portMatch.group(1));
            var pidMatch = SS_PID.matcher(line);
            while (pidMatch.find()) {
                sets.computeIfAbsent(Long.parseLong(pidMatch.group(1)), k -> new TreeSet<>()).add(port);
            }
        }
        var result = new HashMap<Long, List<Integer>>();
        sets.forEach((pid, ports) -> result.put(pid, List.copyOf(ports)));
        return result;
    }

    private static List<String> ssLines() {
        try {
            var process = new ProcessBuilder("ss", "-ltnpH").redirectErrorStream(true).start();
            process.getOutputStream().close();
            var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            return process.exitValue() == 0 ? out.lines().toList() : List.of();
        } catch (IOException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private static List<String> cmdline(long pid) {
        try {
            var raw = Files.readString(Path.of("/proc", String.valueOf(pid), "cmdline"), StandardCharsets.UTF_8);
            var args = new ArrayList<String>();
            for (var part : raw.split("\0")) {
                if (!part.isEmpty()) {
                    args.add(part);
                }
            }
            return args;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String cwd(long pid) {
        try {
            return Files.readSymbolicLink(Path.of("/proc", String.valueOf(pid), "cwd")).toString();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static boolean inContainer(long pid) {
        try {
            var cgroup = Files.readString(Path.of("/proc", String.valueOf(pid), "cgroup"));
            return cgroup.contains("docker") || cgroup.contains("containerd") || cgroup.contains("kubepods");
        } catch (IOException e) {
            return false;
        }
    }
}
