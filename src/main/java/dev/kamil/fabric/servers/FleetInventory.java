package dev.kamil.fabric.servers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The expected servers and apps, read from deployment-center's {@code projects/*}/fleet.env. */
public final class FleetInventory {

    /** Default location of deployment-center. */
    public static final Path DEFAULT_PATH = Path.of(System.getProperty("user.home"), "Documents", "Projects",
            "deployment", "deployment-center");

    /** A server alias with the apps expected on it. */
    public record Server(String alias, List<ExpectedApp> apps) {
    }

    /** An app a fleet.env declares; {@code env} is "prod" or "test". */
    public record ExpectedApp(String project, String env, String remoteDir) {
    }

    private FleetInventory() {
    }

    /** Servers sorted by alias, apps by project then env; a missing directory gives an empty list. */
    public static List<Server> read(Path deploymentCenter) {
        var projects = deploymentCenter.resolve("projects");
        var byAlias = new TreeMap<String, List<ExpectedApp>>();
        if (Files.isDirectory(projects)) {
            try (var dirs = Files.list(projects)) {
                dirs.filter(Files::isDirectory).forEach(dir -> add(byAlias, dir));
            } catch (IOException e) {
                return List.of();
            }
        }
        var servers = new ArrayList<Server>();
        byAlias.forEach((alias, apps) -> {
            apps.sort(Comparator.comparing(ExpectedApp::project).thenComparing(ExpectedApp::env));
            servers.add(new Server(alias, List.copyOf(apps)));
        });
        return servers;
    }

    private static void add(Map<String, List<ExpectedApp>> byAlias, Path dir) {
        var file = dir.resolve("fleet.env");
        if (!Files.isRegularFile(file)) {
            return;
        }
        Map<String, String> env;
        try {
            env = parse(Files.readAllLines(file));
        } catch (IOException e) {
            return;
        }
        var host = env.get("SSH_HOST");
        if (host == null || host.isEmpty()) {
            return;
        }
        var project = dir.getFileName().toString();
        var apps = byAlias.computeIfAbsent(host, k -> new ArrayList<>());
        if (env.get("REMOTE_DIR") != null && !env.get("REMOTE_DIR").isEmpty()) {
            apps.add(new ExpectedApp(project, "prod", env.get("REMOTE_DIR")));
        }
        if (env.get("REMOTE_DIR_TEST") != null && !env.get("REMOTE_DIR_TEST").isEmpty()) {
            apps.add(new ExpectedApp(project, "test", env.get("REMOTE_DIR_TEST")));
        }
    }

    /** KEY="value" lines; comments and lines without '=' are skipped, one pair of quotes is stripped. */
    static Map<String, String> parse(List<String> lines) {
        var values = new HashMap<String, String>();
        for (var raw : lines) {
            var line = raw.strip();
            if (line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring(7).strip();
            }
            var eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            var value = line.substring(eq + 1).strip();
            if (value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')) {
                var end = value.indexOf(value.charAt(0), 1);
                value = end < 0 ? value.substring(1) : value.substring(1, end);
            } else {
                var hash = value.indexOf(" #");
                value = (hash < 0 ? value : value.substring(0, hash)).strip();
            }
            values.put(line.substring(0, eq).strip(), value);
        }
        return values;
    }
}
