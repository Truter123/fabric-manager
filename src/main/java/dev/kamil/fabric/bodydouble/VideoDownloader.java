package dev.kamil.fabric.bodydouble;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Downloads a YouTube video with yt-dlp into a local cache, so it can be played from disk. Blocking: call off the FX thread. */
public final class VideoDownloader {

    static final String FORMAT = "136+140/bv*[vcodec^=avc1][height<=720]+ba[ext=m4a]";
    static final String UPDATE_HINT = "Update yt-dlp: curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"
            + " -o ~/.local/bin/yt-dlp && chmod +x ~/.local/bin/yt-dlp";
    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(30);
    private static final Pattern PROGRESS = Pattern.compile("\\[download]\\s+(\\d+(?:\\.\\d+)?)%");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{6,20}");

    /** Output of a finished process. */
    public record Result(int exitCode, String out, String err) {
    }

    /**
     * Runs a command, feeding each stdout line to onLine as it arrives. Throws IOException when it cannot start,
     * when it exceeds the timeout (message starts with "timeout") and when cancelled (message "cancelled").
     */
    public interface Runner {
        Result run(List<String> command, Duration timeout, Consumer<String> onLine, BooleanSupplier cancelled)
                throws IOException;
    }

    /** Thrown with a message fit to show the user. */
    public static final class ResolveException extends Exception {
        public ResolveException(String message) {
            super(message);
        }
    }

    private final Runner runner;
    private final Path cacheDir;
    private final List<String> binaries;
    private final List<String> runtimeArgs;

    public VideoDownloader() {
        this(VideoDownloader::runProcess,
                defaultCacheDir(),
                List.of(home().resolve(".local/bin/yt-dlp").toString(), "yt-dlp", "/snap/bin/yt-dlp"),
                findNode(home()).map(n -> List.of("--js-runtimes", n)).orElse(List.of()));
    }

    private static Path home() {
        return Path.of(System.getProperty("user.home"));
    }

    public VideoDownloader(Runner runner, Path cacheDir, List<String> binaries, List<String> runtimeArgs) {
        this.runner = runner;
        this.cacheDir = cacheDir;
        this.binaries = binaries;
        this.runtimeArgs = runtimeArgs;
    }

    /** The video id of a YouTube link (watch?v=, youtu.be, shorts, embed, live), or null. */
    static String videoId(String pageUrl) {
        try {
            var uri = URI.create(pageUrl.strip());
            var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            String candidate = null;
            if (host.equals("youtu.be")) {
                candidate = firstSegment(uri.getPath(), 0);
            } else if (host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com")) {
                var query = uri.getRawQuery();
                if (query != null) {
                    for (var pair : query.split("&")) {
                        if (pair.startsWith("v=")) {
                            candidate = pair.substring(2);
                        }
                    }
                }
                if (candidate == null) {
                    var path = uri.getPath() == null ? "" : uri.getPath();
                    for (var prefix : List.of("/shorts/", "/embed/", "/live/", "/v/")) {
                        if (path.startsWith(prefix)) {
                            candidate = firstSegment(path, 1);
                        }
                    }
                }
            }
            return candidate != null && ID.matcher(candidate).matches() ? candidate : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String firstSegment(String path, int index) {
        var parts = (path == null ? "" : path).split("/");
        // parts[0] is empty before the leading slash
        return parts.length > index + 1 ? parts[index + 1] : null;
    }

    /** The cached file for the link, when it was downloaded before. */
    public Optional<Path> cached(String pageUrl) {
        var id = videoId(pageUrl);
        if (id == null) {
            return Optional.empty();
        }
        var file = cacheDir.resolve(id + ".mp4");
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    /** Removes the cached file, so the next load downloads it again. */
    public void deleteCached(String pageUrl) {
        cached(pageUrl).ifPresent(file -> {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // nothing else to do
            }
        });
    }

    /** The local file for the link: the cached one, or a fresh download reporting percent progress. */
    public Path fetch(String pageUrl, IntConsumer progress, BooleanSupplier cancelled) throws ResolveException {
        var hit = cached(pageUrl);
        if (hit.isPresent()) {
            return hit.get();
        }
        var id = videoId(pageUrl);
        if (id == null) {
            throw new ResolveException("Not a YouTube video link");
        }
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new ResolveException("Cannot create cache directory " + cacheDir + ": " + e.getMessage());
        }
        var last = new int[] {-1};
        Consumer<String> onLine = line -> {
            var percent = parsePercent(line);
            if (percent >= 0 && percent != last[0]) {
                last[0] = percent;
                progress.accept(percent);
            }
        };
        run(List.of("-f", FORMAT, "--merge-output-format", "mp4", "--no-playlist", "--newline",
                "-o", cacheDir.resolve(id + ".%(ext)s").toString(), "--", pageUrl), DOWNLOAD_TIMEOUT, onLine, cancelled);
        var file = cached(pageUrl).orElseThrow(() -> new ResolveException("yt-dlp finished but produced no file"));
        var limitMb = dev.kamil.fabric.settings.AppSettings.shared().getInt(CACHE_MB_KEY, DEFAULT_CACHE_MB);
        trimCache(cacheDir, limitMb * 1024L * 1024L, file);
        return file;
    }

    /** Setting with the cache size limit in MB; the oldest videos go first when a download exceeds it. */
    public static final String CACHE_MB_KEY = "bodydouble.cacheMb";

    public static final int DEFAULT_CACHE_MB = 4096;

    public static Path defaultCacheDir() {
        return home().resolve(".cache/fabric-manager/bodydouble");
    }

    /** Deletes every file in the cache folder and returns the bytes freed. */
    public static long clearCache(Path dir) {
        return trimCache(dir, 0, null);
    }

    /** Deletes the oldest files until the folder is within {@code maxBytes}; {@code keep} is never deleted. */
    static long trimCache(Path dir, long maxBytes, Path keep) {
        var files = new java.util.ArrayList<Path>();
        long total = 0;
        try (var stream = Files.list(dir)) {
            for (var file : (Iterable<Path>) stream::iterator) {
                if (Files.isRegularFile(file)) {
                    files.add(file);
                    total += Files.size(file);
                }
            }
            files.sort(java.util.Comparator.comparing((Path f) -> f.toFile().lastModified()));
            long freed = 0;
            for (var file : files) {
                if (total <= maxBytes) {
                    break;
                }
                if (file.equals(keep)) {
                    continue;
                }
                var size = Files.size(file);
                Files.delete(file);
                total -= size;
                freed += size;
            }
            return freed;
        } catch (IOException e) {
            System.err.println("Cannot trim cache " + dir + ": " + e.getMessage());
            return 0;
        }
    }

    /** Percent from a yt-dlp progress line, or -1 when the line is not progress. */
    static int parsePercent(String line) {
        var m = PROGRESS.matcher(line);
        return m.find() ? (int) Double.parseDouble(m.group(1)) : -1;
    }

    /** The video title, or null when it cannot be read. */
    public String title(String pageUrl) {
        try {
            var out = run(List.of("--get-title", "--no-playlist", "--", pageUrl), LOOKUP_TIMEOUT, line -> { }, () -> false).strip();
            return out.isEmpty() ? null : out.lines().findFirst().orElse(null);
        } catch (ResolveException e) {
            return null;
        }
    }

    private String run(List<String> args, Duration timeout, Consumer<String> onLine, BooleanSupplier cancelled)
            throws ResolveException {
        IOException last = null;
        for (var binary : binaries) {
            var command = new ArrayList<String>();
            command.add(binary);
            command.addAll(runtimeArgs);
            command.addAll(args);
            Result result;
            try {
                result = runner.run(command, timeout, onLine, cancelled);
            } catch (IOException e) {
                if ("cancelled".equals(e.getMessage())) {
                    throw new ResolveException("Cancelled");
                }
                last = e;
                continue;
            }
            if (result.exitCode() != 0) {
                var detail = result.err() == null ? "" : result.err().strip();
                var lastLine = detail.lines().reduce((a, b) -> b).orElse("exit code " + result.exitCode());
                var message = "yt-dlp failed: " + lastLine;
                if (lastLine.contains("403") || lastLine.contains("Requested format is not available")) {
                    message += ". " + UPDATE_HINT;
                }
                throw new ResolveException(message);
            }
            return result.out() == null ? "" : result.out();
        }
        if (last != null && last.getMessage() != null && last.getMessage().startsWith("timeout")) {
            throw new ResolveException("yt-dlp timed out after " + timeout.toMinutes() + " min");
        }
        throw new ResolveException("yt-dlp not found. " + UPDATE_HINT);
    }

    /** "node" when it is on PATH, else "node:&lt;path&gt;" of the newest nvm install. */
    static Optional<String> findNode(Path home) {
        var path = System.getenv("PATH");
        if (path != null) {
            for (var dir : path.split(java.io.File.pathSeparator)) {
                if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, "node"))) {
                    return Optional.of("node");
                }
            }
        }
        var versions = home.resolve(".nvm/versions/node");
        if (!Files.isDirectory(versions)) {
            return Optional.empty();
        }
        try (Stream<Path> dirs = Files.list(versions)) {
            return dirs.filter(d -> Files.isExecutable(d.resolve("bin/node")))
                    .max(Comparator.comparing((Path d) -> versionKey(d.getFileName().toString()),
                            VideoDownloader::compareVersions))
                    .map(d -> "node:" + d.resolve("bin/node").toAbsolutePath());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static int[] versionKey(String name) {
        var parts = name.replaceFirst("^v", "").split("\\.");
        var key = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                key[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                key[i] = 0;
            }
        }
        return key;
    }

    private static int compareVersions(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static Result runProcess(List<String> command, Duration timeout, Consumer<String> onLine,
            BooleanSupplier cancelled) throws IOException {
        var process = new ProcessBuilder(command).start();
        process.getOutputStream().close();
        var err = new StringBuffer();
        var errReader = new Thread(() -> {
            try {
                err.append(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // process ended
            }
        });
        errReader.setDaemon(true);
        errReader.start();
        var out = new StringBuffer();
        var outThread = new Thread(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                    onLine.accept(line);
                }
            } catch (IOException ignored) {
                // process ended
            }
        });
        outThread.setDaemon(true);
        outThread.start();
        var deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) {
                    process.destroyForcibly();
                    throw new IOException("cancelled");
                }
                if (System.nanoTime() > deadline) {
                    process.destroyForcibly();
                    throw new IOException("timeout");
                }
            }
            outThread.join(1000);
            errReader.join(1000);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        return new Result(process.exitValue(), out.toString(), err.toString());
    }
}
