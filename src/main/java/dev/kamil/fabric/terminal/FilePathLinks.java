package dev.kamil.fabric.terminal;

import com.techsenger.jeditermfx.core.model.hyperlinks.HyperlinkFilter;
import com.techsenger.jeditermfx.core.model.hyperlinks.LinkInfo;
import com.techsenger.jeditermfx.core.model.hyperlinks.LinkResult;
import com.techsenger.jeditermfx.core.model.hyperlinks.LinkResultItem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Makes existing file paths in terminal output clickable: {@code src/a/B.java:12}, {@code ./run.sh}, {@code /tmp/x:3:7}. */
final class FilePathLinks implements HyperlinkFilter {

    /** A path-like token in a line; start/end are offsets of the whole match including :line:col. */
    record Match(int start, int end, String path, int line, int column) {
    }

    private static final Pattern PATH = Pattern.compile(
            "(?<![\\w./:~@+-])((?:\\.{1,2}/|/)?[\\w.@+-]+(?:/[\\w.@+-]+)*)(?::(\\d+)(?::(\\d+))?)?");

    private final Path directory;

    FilePathLinks(Path directory) {
        this.directory = directory;
    }

    /** Path-shaped tokens (contain a slash or an extension); whether the file exists is not checked here. */
    static List<Match> find(String line) {
        var result = new ArrayList<Match>();
        var m = PATH.matcher(line);
        while (m.find()) {
            var path = m.group(1);
            int end = m.end();
            if (m.group(2) == null) {
                // a sentence-ending dot is not part of the name
                while (path.endsWith(".") && path.length() > 1) {
                    path = path.substring(0, path.length() - 1);
                    end--;
                }
            }
            if (!looksLikePath(path)) {
                continue;
            }
            int lineNo = m.group(2) == null ? 0 : parse(m.group(2));
            int col = m.group(3) == null ? 0 : parse(m.group(3));
            result.add(new Match(m.start(), end, path, lineNo, col));
        }
        return result;
    }

    private static boolean looksLikePath(String path) {
        if (path.equals(".") || path.equals("..") || path.equals("/")) {
            return false;
        }
        if (path.contains("/")) {
            return true;
        }
        int dot = path.lastIndexOf('.');
        return dot > 0 && dot < path.length() - 1;
    }

    private static int parse(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The file a path refers to: absolute as is, otherwise relative to the directory. */
    static Path resolve(Path directory, String path) {
        var p = Path.of(path);
        return p.isAbsolute() ? p.normalize() : directory.resolve(p).normalize();
    }

    /** Command that opens the file: IntelliJ at the line when {@code idea} is known, else xdg-open. */
    static List<String> command(String idea, Path file, int line) {
        if (idea == null) {
            return List.of("xdg-open", file.toString());
        }
        return line > 0
                ? List.of(idea, "--line", String.valueOf(line), file.toString())
                : List.of(idea, file.toString());
    }

    /** {@code ~/.local/bin/idea}, else {@code idea} on the PATH, else null. */
    static String findIdea(String home, String pathVariable) {
        if (home != null) {
            var local = Path.of(home, ".local", "bin", "idea");
            if (Files.isExecutable(local)) {
                return local.toString();
            }
        }
        if (pathVariable != null) {
            for (var dir : pathVariable.split(":")) {
                if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, "idea"))) {
                    return Path.of(dir, "idea").toString();
                }
            }
        }
        return null;
    }

    @Override
    public LinkResult apply(String line) {
        List<LinkResultItem> items = null;
        for (var match : find(line)) {
            var file = resolve(directory, match.path());
            if (!Files.exists(file)) {
                continue;
            }
            if (items == null) {
                items = new ArrayList<>();
            }
            items.add(new LinkResultItem(match.start(), match.end(),
                    new LinkInfo(() -> open(file, match.line()))));
        }
        return items == null ? null : new LinkResult(items);
    }

    private static void open(Path file, int line) {
        var command = command(findIdea(System.getProperty("user.home"), System.getenv("PATH")), file, line);
        Thread.startVirtualThread(() -> {
            try {
                new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
            } catch (IOException e) {
                System.err.println("Cannot open " + file + ": " + e.getMessage());
            }
        });
    }
}
