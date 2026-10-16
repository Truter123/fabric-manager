package dev.kamil.fabric.claude.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Token usage of one Claude Code transcript (JSONL). Read incrementally: only lines appended since the previous
 * {@link #refresh()} are parsed. The same assistant message can appear on several lines; the last one wins.
 */
public final class TranscriptUsage {

    static final long DEFAULT_WINDOW = 200_000;
    static final long LARGE_WINDOW = 1_000_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Usage of one assistant message. */
    public record Turn(String id, String model, Instant time, long input, long output, long cacheWrite,
            long cacheRead) {

        /** Tokens in the context window after this turn. */
        public long context() {
            return input + cacheRead + cacheWrite;
        }

        public long total() {
            return input + output + cacheWrite + cacheRead;
        }
    }

    private final Path file;
    private long offset;
    private final Map<String, Turn> turns = new LinkedHashMap<>();
    private Turn last;

    public TranscriptUsage(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** Parses one JSONL line; empty unless it is an assistant line with message.usage. */
    static Optional<Turn> parseLine(String line) {
        if (line == null || !line.contains("\"usage\"")) {
            return Optional.empty();
        }
        try {
            var root = JSON.readTree(line);
            var message = root.path("message");
            var usage = message.path("usage");
            if (!"assistant".equals(root.path("type").asText()) || !usage.isObject()) {
                return Optional.empty();
            }
            var model = message.path("model").asText("");
            if (model.startsWith("<")) {
                return Optional.empty();
            }
            var id = message.path("id").asText("");
            if (id.isEmpty()) {
                id = root.path("uuid").asText("");
            }
            Instant time = null;
            var stamp = root.path("timestamp").asText("");
            if (!stamp.isEmpty()) {
                time = Instant.parse(stamp);
            }
            return Optional.of(new Turn(id, model, time, num(usage, "input_tokens"), num(usage, "output_tokens"),
                    num(usage, "cache_creation_input_tokens"), num(usage, "cache_read_input_tokens")));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static long num(JsonNode node, String field) {
        return node.path(field).asLong(0);
    }

    /** Reads the complete lines appended since the last call. A shrunk file is read again from the start. */
    public synchronized TranscriptUsage refresh() throws IOException {
        if (!Files.isRegularFile(file)) {
            return this;
        }
        for (var line : readNew()) {
            parseLine(line).ifPresent(turn -> {
                var key = turn.id().isEmpty() ? "line-" + turns.size() : turn.id();
                turns.remove(key);
                turns.put(key, turn);
                last = turn;
            });
        }
        return this;
    }

    private List<String> readNew() throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < offset) {
                offset = 0;
                turns.clear();
                last = null;
            }
            var buffer = ByteBuffer.allocate((int) (size - offset));
            channel.position(offset);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // fill
            }
            var bytes = buffer.array();
            int end = buffer.position();
            while (end > 0 && bytes[end - 1] != '\n') {
                end--;
            }
            offset += end;
            var lines = new ArrayList<String>();
            for (var line : new String(bytes, 0, end, StandardCharsets.UTF_8).split("\\R")) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
            return lines;
        }
    }

    /** Bytes of the file read so far. */
    public synchronized long offset() {
        return offset;
    }

    /** Distinct assistant messages, in order of last appearance. */
    public synchronized Collection<Turn> turns() {
        return List.copyOf(turns.values());
    }

    /** Context tokens after the last assistant turn: input + cache read + cache creation. */
    public synchronized long contextTokens() {
        return last == null ? 0 : last.context();
    }

    /** Model of the last assistant turn, or empty. */
    public synchronized String model() {
        return last == null ? "" : last.model();
    }

    /** Window size: 1M for a "[1m]" model id, an Opus 5 model or a context beyond 200k, else 200k. */
    public synchronized long window() {
        return windowFor(model(), contextTokens());
    }

    static long windowFor(String model, long context) {
        var id = model.toLowerCase();
        return id.contains("[1m]") || id.startsWith("claude-opus-5") || context > DEFAULT_WINDOW
                ? LARGE_WINDOW : DEFAULT_WINDOW;
    }

    /** Context as a percentage of the window, 0 to 100. */
    public synchronized double contextPercent() {
        return 100.0 * contextTokens() / window();
    }

    /** Sum over all distinct assistant messages of the session. */
    public synchronized long totalTokens() {
        return turns.values().stream().mapToLong(Turn::total).sum();
    }

    public synchronized long totalOutputTokens() {
        return turns.values().stream().mapToLong(Turn::output).sum();
    }
}
