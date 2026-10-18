package dev.kamil.fabric.servers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns the probe output (three sections split by a line "---") into a {@link ServerStatus}. */
public final class ProbeParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TAG_PREFIX = "backend-";

    private ProbeParser() {
    }

    public static ServerStatus parse(String alias, ServerProbe.Output output) {
        if (output.exitCode() == 255 || output.exitCode() == ServerProbe.TIMEOUT) {
            return unreachable(alias, output.error());
        }
        var sections = split(output.stdout());
        if (output.exitCode() != 0 && sections.size() < 3) {
            return unreachable(alias, output.error());
        }
        while (sections.size() < 3) {
            sections.add("");
        }
        try {
            return new ServerStatus(alias, true, null, apps(sections.get(0), sections.get(1)),
                    fleetVersions(sections.get(2)));
        } catch (IOException | RuntimeException e) {
            return unreachable(alias, "unreadable probe output: " + e.getMessage());
        }
    }

    private static ServerStatus unreachable(String alias, String error) {
        var text = error == null || error.isBlank() ? "unreachable" : error;
        return new ServerStatus(alias, false, text, List.of(), Map.of());
    }

    private static List<String> split(String stdout) {
        var sections = new ArrayList<String>();
        var current = new StringBuilder();
        for (var line : stdout.split("\n", -1)) {
            if (line.strip().equals("---")) {
                sections.add(current.toString());
                current.setLength(0);
            } else {
                current.append(line).append('\n');
            }
        }
        sections.add(current.toString());
        return sections;
    }

    private static Map<String, String> composeProjects(String section) throws IOException {
        var names = new LinkedHashMap<String, String>();
        try (var values = JSON.readerFor(JsonNode.class).readValues(section)) {
            while (values.hasNext()) {
                var node = (JsonNode) values.next();
                if (node.isArray()) {
                    node.forEach(item -> addName(names, item));
                } else {
                    addName(names, node);
                }
            }
        }
        return names;
    }

    private static void addName(Map<String, String> names, JsonNode item) {
        var name = item.path("Name").asText("");
        if (!name.isEmpty() && !names.containsKey(name)) {
            names.put(name, remoteDir(item.path("ConfigFiles").asText("")));
        }
    }

    /** Parent directory name of the first config file path, or null. */
    private static String remoteDir(String configFiles) {
        var first = configFiles.split(",")[0].strip();
        var parent = first.isEmpty() ? null : Path.of(first).getParent();
        var dir = parent == null ? null : parent.getFileName();
        return dir == null ? null : dir.toString();
    }

    private static List<AppStatus> apps(String composeSection, String inspectSection) throws IOException {
        var byProject = new LinkedHashMap<String, List<ContainerStatus>>();
        var dirs = composeProjects(composeSection);
        for (var name : dirs.keySet()) {
            byProject.put(name, new ArrayList<>());
        }
        for (var line : inspectSection.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            var fields = new ArrayList<JsonNode>();
            try (var values = JSON.readerFor(JsonNode.class).readValues(line)) {
                while (values.hasNext()) {
                    fields.add((JsonNode) values.next());
                }
            }
            if (fields.size() < 6 || fields.get(5).isNull()) {
                continue;
            }
            var name = fields.get(0).asText();
            var container = new ContainerStatus(name.startsWith("/") ? name.substring(1) : name,
                    fields.get(1).asText(), instant(fields.get(2).asText()),
                    fields.get(3).isNull() ? null : fields.get(3).asText(), fields.get(4).asText());
            byProject.computeIfAbsent(fields.get(5).asText(), k -> new ArrayList<>()).add(container);
        }
        var apps = new ArrayList<AppStatus>();
        byProject.forEach((project, containers) -> apps.add(app(project, dirs.get(project), containers)));
        return apps;
    }

    private static AppStatus app(String project, String remoteDir, List<ContainerStatus> containers) {
        var running = containers.stream().filter(c -> c.state().equals("running")).toList();
        var unhealthy = containers.stream().anyMatch(c -> "unhealthy".equals(c.health()));
        AppState state;
        if (running.isEmpty()) {
            state = AppState.DOWN;
        } else if (running.size() == containers.size() && !unhealthy) {
            state = AppState.UP;
        } else {
            state = AppState.DEGRADED;
        }
        var startedAt = running.stream().map(ContainerStatus::startedAt).filter(i -> i != null)
                .min(Instant::compareTo).orElse(null);
        return new AppStatus(project, remoteDir, state, imageVersion(containers), startedAt, List.copyOf(containers));
    }

    private static String imageVersion(List<ContainerStatus> containers) {
        for (var container : containers) {
            var image = container.image();
            var at = image.indexOf(TAG_PREFIX);
            if (at >= 0 && at + TAG_PREFIX.length() < image.length()) {
                return image.substring(at + TAG_PREFIX.length());
            }
        }
        return null;
    }

    private static Instant instant(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Map<String, String> fleetVersions(String section) {
        var versions = new LinkedHashMap<String, String>();
        for (var line : section.split("\n")) {
            var parts = line.strip().split("\\s+");
            if (parts.length == 2) {
                versions.put(parts[0], parts[1]);
            }
        }
        return versions;
    }
}
