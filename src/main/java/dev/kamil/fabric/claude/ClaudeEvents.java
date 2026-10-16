package dev.kamil.fabric.claude;

import dev.kamil.fabric.terminal.TerminalTab;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import javafx.application.Platform;

/**
 * Session events from Claude Code. A small plugin (loaded with --plugin-dir) has hooks append a state word or an
 * agent line to $FABRIC_EVENTS/$FABRIC_SESSION; a watcher on that directory reports the new lines of each file.
 */
public final class ClaudeEvents {

    private static final String PLUGIN_JSON =
            "{\"name\":\"fabric-notify\",\"version\":\"0.1.0\",\"description\":\"FabricManager session events\"}";

    private ClaudeEvents() {
    }

    /** One hook entry: event name, optional matcher, word written to the events file. */
    private record Hook(String event, String matcher, String word) {
    }

    private static final List<Hook> HOOKS = List.of(
            new Hook("UserPromptSubmit", null, "working"),
            new Hook("PostToolUse", null, "working"),
            new Hook("Stop", null, "done"),
            new Hook("StopFailure", null, "error"),
            new Hook("PermissionRequest", null, "waiting"),
            new Hook("PreToolUse", "AskUserQuestion|ExitPlanMode", "waiting"),
            new Hook("Notification", "elicitation_dialog", "waiting"));

    /** Shell command of a hook: appends its word to the events file of the session. */
    private static String command(Hook hook) {
        var file = "\"$FABRIC_EVENTS/$FABRIC_SESSION\"";
        return "[ -n \"$FABRIC_EVENTS\" ] && printf '" + hook.word() + "\\n' >> " + file + " || true" + TRANSCRIPT_CAPTURE;
    }

    /**
     * Appended to every hook command: Claude Code passes JSON on stdin; its transcript_path is written to
     * $FABRIC_EVENTS/$FABRIC_SESSION.transcript. No jq needed.
     */
    private static final String TRANSCRIPT_CAPTURE = "; t=$(sed -n 's/.*\"transcript_path\" *: *\"\\([^\"]*\\)\".*/\\1/p' | head -n 1);"
            + " [ -n \"$FABRIC_EVENTS\" ] && [ -n \"$FABRIC_SESSION\" ] && [ -n \"$t\" ]"
            + " && printf '%s\\n' \"$t\" > \"$FABRIC_EVENTS/$FABRIC_SESSION.transcript\" || true";

    /** Transcript file of a session as reported by its hooks; empty when no hook has fired yet. */
    public static java.util.Optional<Path> transcript(Path eventsDir, String session) {
        try {
            var text = Files.readString(eventsDir.resolve(session + ".transcript")).strip();
            return text.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(Path.of(text));
        } catch (IOException | RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    /** hooks.json: hooks of the same event share one array entry list, one matcher group each. */
    static String hooksJson() {
        var byEvent = new java.util.LinkedHashMap<String, List<Hook>>();
        for (var hook : HOOKS) {
            byEvent.computeIfAbsent(hook.event(), k -> new java.util.ArrayList<>()).add(hook);
        }
        var json = new StringBuilder("{\"hooks\":{");
        boolean firstEvent = true;
        for (var entry : byEvent.entrySet()) {
            if (!firstEvent) {
                json.append(',');
            }
            firstEvent = false;
            json.append('"').append(entry.getKey()).append("\":[");
            boolean firstGroup = true;
            for (var hook : entry.getValue()) {
                if (!firstGroup) {
                    json.append(',');
                }
                firstGroup = false;
                json.append('{');
                if (hook.matcher() != null) {
                    json.append("\"matcher\":\"").append(escape(hook.matcher())).append("\",");
                }
                json.append("\"hooks\":[{\"type\":\"command\",\"command\":\"").append(escape(command(hook)))
                        .append('"');
                json.append("}]}");
            }
            json.append(']');
        }
        return json.append("}}").toString();
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static Path pluginDir() {
        return Path.of(System.getProperty("user.home"), ".config/fabric-manager/claude-plugin");
    }

    /** Writes the plugin files (overwritten at every start) and returns the plugin directory. */
    public static Path installPlugin(Path dir) {
        try {
            Files.createDirectories(dir.resolve(".claude-plugin"));
            Files.createDirectories(dir.resolve("hooks"));
            Files.writeString(dir.resolve(".claude-plugin/plugin.json"), PLUGIN_JSON);
            Files.writeString(dir.resolve("hooks/hooks.json"), hooksJson());
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the plugin to " + dir, e);
        }
    }

    public static Path eventsDir() {
        return eventsDir(System.getenv("XDG_RUNTIME_DIR"), Path.of(System.getProperty("user.home")));
    }

    static Path eventsDir(String runtimeDir, Path home) {
        return runtimeDir == null || runtimeDir.isBlank()
                ? home.resolve(".cache/fabric-manager/events")
                : Path.of(runtimeDir, "fabric-manager/events");
    }

    /** State for the last line of an events file; null when unknown. */
    static TerminalTab.Activity parse(String lastLine) {
        if (lastLine == null) {
            return null;
        }
        return switch (lastLine.strip().toLowerCase(Locale.ROOT)) {
            case "working" -> TerminalTab.Activity.WORKING;
            case "waiting" -> TerminalTab.Activity.WAITING;
            case "done" -> TerminalTab.Activity.DONE;
            case "error" -> TerminalTab.Activity.ERROR;
            default -> null;
        };
    }

    /** One parsed line of an events file. */
    public sealed interface Event {
    }

    /** A state word: the session is working, waiting, done or failed. */
    public record ActivityChanged(TerminalTab.Activity activity) implements Event {
    }

    /** Event for one line of an events file; null when the line is unknown or malformed. */
    public static Event parseLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        var text = line.strip();
        var activity = parse(text);
        if (activity != null) {
            return new ActivityChanged(activity);
        }
        return null;
    }

    /** New complete lines of a file since an offset, and the offset after them. */
    record Chunk(List<String> lines, long offset) {
    }

    /**
     * Reads the complete lines appended after offset; a partial last line stays unread. A file shorter than
     * the offset was truncated and is read from the start.
     */
    static Chunk readNew(Path file, long offset) throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.READ)) {
            long start = channel.size() < offset ? 0 : offset;
            var buffer = java.nio.ByteBuffer.allocate((int) (channel.size() - start));
            channel.position(start);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // fill
            }
            var bytes = buffer.array();
            int end = buffer.position();
            while (end > 0 && bytes[end - 1] != '\n') {
                end--;
            }
            var lines = new java.util.ArrayList<String>();
            for (var line : new String(bytes, 0, end, StandardCharsets.UTF_8).split("\\R")) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
            return new Chunk(lines, start + end);
        }
    }

    /** Last non-empty line of the text, or null. */
    static String lastLine(String text) {
        var lines = text.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i];
            }
        }
        return null;
    }

    /**
     * Deletes event files that are not a live session id and were not written for a day. The age check
     * keeps the files of another FabricManager instance, which shares this directory.
     */
    static void clearStale(Path dir, java.util.Set<String> liveSessions) {
        var cutoff = java.time.Instant.now().minus(java.time.Duration.ofDays(1));
        try (var files = Files.list(dir)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                if (!liveSessions.contains(file.getFileName().toString())
                        && Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException | RuntimeException e) {
            // best effort
        }
    }

    /** Watches the events directory on a daemon thread; the callback runs on the FX thread. */
    public static final class Watcher implements AutoCloseable {

        private final WatchService service;

        private Watcher(WatchService service) {
            this.service = service;
        }

        public static Watcher start(Path dir, BiConsumer<String, List<Event>> callback) {
            try {
                Files.createDirectories(dir);
                // At startup this instance has no tab yet; old files are from killed sessions.
                clearStale(dir, java.util.Set.of());
                var service = dir.getFileSystem().newWatchService();
                dir.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
                Thread.ofPlatform().daemon().name("claude-events").start(() -> loop(service, dir, callback, new HashMap<>()));
                return new Watcher(service);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot watch " + dir, e);
            }
        }

        private static void loop(WatchService service, Path dir, BiConsumer<String, List<Event>> callback,
                Map<Path, Long> offsets) {
            try {
                while (true) {
                    var key = service.take();
                    for (var event : key.pollEvents()) {
                        if (event.context() instanceof Path name) {
                            deliver(dir.resolve(name), name.toString(), callback, offsets);
                        }
                    }
                    if (!key.reset()) {
                        return;
                    }
                }
            } catch (InterruptedException | ClosedWatchServiceException e) {
                // stopped
            }
        }

        private static void deliver(Path file, String session, BiConsumer<String, List<Event>> callback,
                Map<Path, Long> offsets) {
            try {
                var chunk = readNew(file, offsets.getOrDefault(file, 0L));
                offsets.put(file, chunk.offset());
                var events = chunk.lines().stream().map(ClaudeEvents::parseLine).filter(Objects::nonNull).toList();
                if (!events.isEmpty()) {
                    Platform.runLater(() -> callback.accept(session, events));
                }
            } catch (IOException | RuntimeException e) {
                // file gone or half written: the next modify event brings it again
            }
        }

        @Override
        public void close() {
            try {
                service.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
