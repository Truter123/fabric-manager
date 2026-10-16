package dev.kamil.fabric.processes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Docker driven through the CLI. Methods block; call them off the UI thread. */
public final class Docker {

    /** Containers plus an error message (null when listing worked). */
    public record Listing(List<DockerContainer> containers, String error) {
    }

    private static final String FORMAT = "{{.ID}}|{{.Names}}|{{.Image}}|{{.State}}|{{.Status}}|{{.Ports}}";
    private static final long TIMEOUT_SECONDS = 30;

    public Listing list() {
        try {
            var result = run(List.of("docker", "ps", "-a", "--format", FORMAT));
            if (result.code != 0) {
                return new Listing(List.of(), "docker not available: " + result.message());
            }
            return new Listing(parse(result.out.lines().toList()), null);
        } catch (IOException e) {
            return new Listing(List.of(), "docker not available: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Listing(List.of(), "docker not available: interrupted");
        }
    }

    /** Parses lines of the {@link #FORMAT}; malformed lines are skipped. */
    public static List<DockerContainer> parse(List<String> lines) {
        var list = new ArrayList<DockerContainer>();
        for (var line : lines) {
            if (line.isBlank()) {
                continue;
            }
            var f = line.split("\\|", 6);
            if (f.length < 5) {
                continue;
            }
            list.add(new DockerContainer(f[0], f[1], f[2], f[4], f.length > 5 ? f[5] : "",
                    "running".equals(f[3])));
        }
        return list;
    }

    /** Runs {@code docker <verb> <id>}; returns null on success, else the error message. */
    public String action(String verb, String id) {
        try {
            var result = run(List.of("docker", verb, id));
            return result.code == 0 ? null : result.message();
        } catch (IOException e) {
            return "docker not available: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    public String start(String id) {
        return action("start", id);
    }

    public String stop(String id) {
        return action("stop", id);
    }

    public String restart(String id) {
        return action("restart", id);
    }

    public String remove(String id) {
        return action("rm", id);
    }

    private record Result(int code, String out, String err) {
        String message() {
            var text = err.isBlank() ? out : err;
            return text.strip();
        }
    }

    private static Result run(List<String> command) throws IOException, InterruptedException {
        var process = new ProcessBuilder(command).start();
        process.getOutputStream().close();
        var err = new StringBuilder();
        var errReader = new Thread(() -> {
            try {
                err.append(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // process gone
            }
        });
        errReader.setDaemon(true);
        errReader.start();
        var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return new Result(-1, out, "timed out");
        }
        errReader.join(1000);
        return new Result(process.exitValue(), out, err.toString());
    }
}
