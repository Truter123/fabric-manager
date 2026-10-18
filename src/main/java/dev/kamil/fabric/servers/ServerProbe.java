package dev.kamil.fabric.servers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs the system ssh binary once per server and returns the raw probe output. */
public final class ServerProbe {

    /** Exit code reported when the probe timed out. */
    public static final int TIMEOUT = -1;

    /** Raw result: stdout, the exit code and stderr (or the failure text). */
    public record Output(String stdout, int exitCode, String error) {
    }

    static final long TIMEOUT_SECONDS = 15;

    static final String SCRIPT = """
            docker compose ls --all --format json
            echo '---'
            docker ps -aq | xargs -r docker inspect --format '{{json .Name}} {{json .State.Status}} {{json .State.StartedAt}} {{if .State.Health}}{{json .State.Health.Status}}{{else}}null{{end}} {{json .Config.Image}} {{json (index .Config.Labels "com.docker.compose.project")}}'
            echo '---'
            for f in ~/.fleet-state/*/current; do echo "$(basename "$(dirname "$f")") $(cat "$f")"; done
            """;

    private final Path cacheDir;

    public ServerProbe() {
        this(Path.of(System.getProperty("user.home"), ".cache", "fabric-manager"));
    }

    public ServerProbe(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /** The ssh command for an alias; does not run anything. */
    public List<String> command(String alias) {
        return List.of("ssh",
                "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=5",
                "-o", "ControlMaster=auto",
                "-o", "ControlPath=" + cacheDir.resolve("ssh-%C"),
                "-o", "ControlPersist=60",
                "--", alias, SCRIPT);
    }

    /** Runs the probe with a 15 s timeout; never throws. */
    public Output run(String alias) {
        try {
            Files.createDirectories(cacheDir);
            var process = new ProcessBuilder(command(alias)).redirectInput(ProcessBuilder.Redirect.from(
                    Path.of("/dev/null").toFile())).start();
            var out = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
            var err = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Output("", TIMEOUT, "timed out after " + TIMEOUT_SECONDS + " s");
            }
            return new Output(out.get(2, TimeUnit.SECONDS), process.exitValue(), err.get(2, TimeUnit.SECONDS).strip());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Output("", TIMEOUT, "interrupted");
        } catch (IOException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return new Output("", TIMEOUT, String.valueOf(e.getMessage()));
        }
    }

    private static String read(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
