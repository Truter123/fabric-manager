package dev.kamil.fabric.notes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Rewrites text as natural English by running a command (the local claude CLI) with the text on stdin. */
public final class EnglishCorrector {

    private static final String PROMPT = "Rewrite the text from stdin as natural, correct English."
            + " Reply with the corrected text only, no comments, no quotes.";
    private static final long TIMEOUT_SECONDS = 60;

    private final List<String> command;

    public EnglishCorrector(List<String> command) {
        this.command = command;
    }

    /** The claude CLI on the haiku model. */
    public static List<String> standard() {
        return List.of("claude", "-p", "--model", "haiku", PROMPT);
    }

    /** Returns the corrected text; throws when the command fails or runs longer than 60 seconds. */
    public String correct(String text) throws IOException {
        var err = Files.createTempFile("fabric-corrector", ".err");
        try {
            var process = new ProcessBuilder(command).redirectError(err.toFile()).start();
            var out = new byte[1][];
            var reader = new Thread(() -> {
                try {
                    out[0] = process.getInputStream().readAllBytes();
                } catch (IOException e) {
                    out[0] = new byte[0];
                }
            });
            reader.setDaemon(true);
            reader.start();
            try (var stdin = process.getOutputStream()) {
                stdin.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // the command may exit without reading stdin; its exit code decides
            }
            try {
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException("Timed out after " + TIMEOUT_SECONDS + " s");
                }
                reader.join();
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
            if (process.exitValue() != 0) {
                throw new IOException("Exit " + process.exitValue() + ": "
                        + Files.readString(err, StandardCharsets.UTF_8).strip());
            }
            return new String(out[0], StandardCharsets.UTF_8).strip();
        } finally {
            Files.deleteIfExists(err);
        }
    }
}
