package dev.kamil.fabric.english;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javafx.application.Platform;

/** Text-to-speech WAVs of the English sentences, cached on disk: piper first, espeak-ng as fallback; no UI. */
final class Voice {

    /** Where the cached WAVs live; tests point it at a temporary directory. */
    static Path cacheDir = Path.of("/home/kamil/Documents/Tools/english/tts");
    static Path piper = Path.of("/home/kamil/.local/bin/piper");
    static Path model = Path.of("/home/kamil/.local/share/piper/en_US-lessac-medium.onnx");
    /** Tests set this: no engine is started; a tiny file is written and the request is logged. */
    static volatile boolean fake;
    /** What the fake engine was asked for: {@code voice:normal:<text>} or {@code voice:slow:<text>}. */
    static final List<String> LOG = Collections.synchronizedList(new ArrayList<>());

    private static final int TIMEOUT_SECONDS = 60;

    private Voice() {
    }

    /** The cache key of a request: the file name without extension. */
    static String key(String text, boolean slow) {
        try {
            var sha = MessageDigest.getInstance("SHA-1")
                    .digest(((slow ? "slow:" : "") + text).getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (byte b : sha) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The WAV of {@code text}, made on first use and reused after; blocks while the engine runs. */
    static synchronized Path wav(String text, boolean slow) throws IOException {
        var target = cacheDir.resolve(key(text, slow) + ".wav");
        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            return target;
        }
        Files.createDirectories(cacheDir);
        var temp = Files.createTempFile(cacheDir, "voice", ".wav");
        try {
            synthesize(text, slow, temp);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return target;
    }

    /** Runs {@link #wav} on a background thread and calls {@code ok} or {@code fail} (with a message) on the FX thread. */
    static void async(String text, boolean slow, Consumer<Path> ok, Consumer<String> fail) {
        var thread = new Thread(() -> {
            try {
                var wav = wav(text, slow);
                Platform.runLater(() -> ok.accept(wav));
            } catch (IOException e) {
                Platform.runLater(() -> fail.accept(e.getMessage()));
            }
        }, "voice");
        thread.setDaemon(true);
        thread.start();
    }

    private static void synthesize(String text, boolean slow, Path out) throws IOException {
        if (fake) {
            LOG.add("voice:" + (slow ? "slow" : "normal") + ":" + text);
            Files.write(out, new byte[] {1, 2, 3});
            return;
        }
        String failure;
        try {
            if (Files.isExecutable(piper) && Files.isRegularFile(model)) {
                var command = new ArrayList<>(List.of(piper.toString(), "-m", model.toString(), "-f", out.toString()));
                if (slow) {
                    command.addAll(List.of("--length-scale", "1.4"));
                }
                if (run(command, text + "\n", out)) {
                    return;
                }
                failure = "piper failed";
            } else {
                failure = "piper or its model is missing";
            }
        } catch (IOException e) {
            failure = "piper failed: " + e.getMessage();
        }
        try {
            var command = new ArrayList<>(List.of("espeak-ng", "-v", "en-us", "-w", out.toString()));
            if (slow) {
                command.addAll(List.of("-s", "120"));
            }
            command.add(text);
            if (run(command, null, out)) {
                return;
            }
        } catch (IOException e) {
            throw new IOException("Cannot make the voice: " + failure + "; espeak-ng failed: " + e.getMessage(), e);
        }
        throw new IOException("Cannot make the voice: " + failure + "; espeak-ng failed too");
    }

    /** Runs the command (stdin text optional); true when it exited 0 and wrote a non-empty {@code out}. */
    private static boolean run(List<String> command, String stdin, Path out) throws IOException {
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        var process = builder.start();
        try (var in = process.getOutputStream()) {
            if (stdin != null) {
                in.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
        }
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return false;
        }
        return process.exitValue() == 0 && Files.isRegularFile(out) && Files.size(out) > 0;
    }
}
