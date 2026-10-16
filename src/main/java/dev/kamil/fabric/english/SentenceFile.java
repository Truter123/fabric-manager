package dev.kamil.fabric.english;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Reads and writes the everyday sentences Markdown file. */
public final class SentenceFile {

    private static final String TITLE = "# Everyday sentences";

    private SentenceFile() {
    }

    public static List<Sentence> parse(String md) {
        var result = new ArrayList<Sentence>();
        String date = null;
        String english = "";
        String pl = "";
        String say = "";
        String watch = "";
        boolean open = false;
        for (var raw : md.split("\r?\n", -1)) {
            var line = raw.strip();
            if (line.startsWith("### ")) {
                if (open) {
                    result.add(new Sentence(date, english, pl, say, watch));
                }
                open = true;
                date = line.substring(4).strip();
                english = "";
                pl = "";
                say = "";
                watch = "";
            } else if (!open) {
                continue;
            } else if (line.startsWith("English:")) {
                english = line.substring(8).strip();
            } else if (line.startsWith("PL:")) {
                pl = line.substring(3).strip();
            } else if (line.startsWith("Say:")) {
                say = stripBackticks(line.substring(4).strip());
            } else if (line.startsWith("Watch:")) {
                watch = line.substring(6).strip();
            }
        }
        if (open) {
            result.add(new Sentence(date, english, pl, say, watch));
        }
        return result;
    }

    private static String stripBackticks(String text) {
        var s = text;
        if (s.startsWith("`")) {
            s = s.substring(1);
        }
        if (s.endsWith("`")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public static String format(List<Sentence> sentences) {
        var out = new StringBuilder(TITLE).append("\n");
        for (var s : sentences) {
            out.append("\n### ").append(s.date()).append('\n')
                    .append("English: ").append(s.english()).append('\n')
                    .append("PL: ").append(s.pl()).append('\n')
                    .append("Say: `").append(s.say()).append("`\n")
                    .append("Watch: ").append(s.watch()).append('\n');
        }
        return out.toString();
    }

    public static List<Sentence> load(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Backs the existing file up to {@code <name>.bak}, then replaces it atomically. */
    public static void save(Path file, List<Sentence> sentences) throws IOException {
        var dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        if (Files.exists(file)) {
            Files.copy(file, dir.resolve(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        }
        var tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, format(sentences), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
