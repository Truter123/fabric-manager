package dev.kamil.fabric.git;

import dev.kamil.fabric.session.HistoryWindow;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Pure parsing of a unified diff into display rows with old/new line numbers. File headers are dropped. */
public final class DiffView {

    /** oldNo / newNo are 0 when the row has no number on that side. */
    public record Row(int oldNo, int newNo, String text, HistoryWindow.DiffKind kind) {
    }

    public record Parsed(List<Row> rows, int added, int removed, boolean binary) {
    }

    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");

    private DiffView() {
    }

    /** At most {@code maxRows} rows (then a truncation row); the +/- counts always cover the whole diff. */
    public static Parsed parse(String diff, int maxRows) {
        var rows = new ArrayList<Row>();
        var added = 0;
        var removed = 0;
        var inHunk = false;
        var truncated = false;
        var binary = false;
        var oldNo = 0;
        var newNo = 0;
        for (var line : diff.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            var hunk = HUNK.matcher(line);
            if (hunk.matches()) {
                inHunk = true;
                oldNo = Integer.parseInt(hunk.group(1));
                newNo = Integer.parseInt(hunk.group(2));
                truncated = add(rows, maxRows, truncated, new Row(0, 0, line, HistoryWindow.DiffKind.HUNK));
                continue;
            }
            if (line.startsWith("diff ")) {
                inHunk = false;
                continue;
            }
            if (!inHunk) {
                binary |= line.startsWith("Binary files ") || line.startsWith("GIT binary patch");
                continue;
            }
            Row row;
            if (line.startsWith("+")) {
                added++;
                row = new Row(0, newNo++, line.substring(1), HistoryWindow.DiffKind.ADD);
            } else if (line.startsWith("-")) {
                removed++;
                row = new Row(oldNo++, 0, line.substring(1), HistoryWindow.DiffKind.REMOVE);
            } else if (line.startsWith("\\")) {
                row = new Row(0, 0, line, HistoryWindow.DiffKind.HEADER);
            } else {
                row = new Row(oldNo++, newNo++, line.substring(1), HistoryWindow.DiffKind.CONTEXT);
            }
            truncated = add(rows, maxRows, truncated, row);
        }
        return new Parsed(List.copyOf(rows), added, removed, binary && rows.isEmpty());
    }

    private static boolean add(List<Row> rows, int maxRows, boolean truncated, Row row) {
        if (truncated) {
            return true;
        }
        if (rows.size() >= maxRows) {
            rows.add(new Row(0, 0, "… diff truncated", HistoryWindow.DiffKind.HEADER));
            return true;
        }
        rows.add(row);
        return false;
    }
}
