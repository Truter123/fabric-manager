package dev.kamil.fabric.git;

import dev.kamil.fabric.session.HistoryWindow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DiffViewTest {

    private static final String DIFF = """
            diff --git a/x.txt b/x.txt
            index 111..222 100644
            --- a/x.txt
            +++ b/x.txt
            @@ -3,4 +3,5 @@ context
             keep
            -old
            +new
            +extra
             tail
            \\ No newline at end of file
            @@ -20 +21 @@
            -a
            +b
            """;

    @Test
    void numbersLinesAndCounts() {
        var parsed = DiffView.parse(DIFF, 100);
        assertEquals(3, parsed.added());
        assertEquals(2, parsed.removed());
        assertFalse(parsed.binary());
        var rows = parsed.rows();
        assertEquals(10, rows.size());
        assertEquals(HistoryWindow.DiffKind.HUNK, rows.get(0).kind());
        assertEquals(new DiffView.Row(3, 3, "keep", HistoryWindow.DiffKind.CONTEXT), rows.get(1));
        assertEquals(new DiffView.Row(4, 0, "old", HistoryWindow.DiffKind.REMOVE), rows.get(2));
        assertEquals(new DiffView.Row(0, 4, "new", HistoryWindow.DiffKind.ADD), rows.get(3));
        assertEquals(new DiffView.Row(0, 5, "extra", HistoryWindow.DiffKind.ADD), rows.get(4));
        assertEquals(new DiffView.Row(5, 6, "tail", HistoryWindow.DiffKind.CONTEXT), rows.get(5));
        assertEquals(HistoryWindow.DiffKind.HEADER, rows.get(6).kind());
        assertEquals(new DiffView.Row(20, 0, "a", HistoryWindow.DiffKind.REMOVE), rows.get(8));
        assertEquals(new DiffView.Row(0, 21, "b", HistoryWindow.DiffKind.ADD), rows.get(9));
    }

    @Test
    void binaryFileHasNoRows() {
        var parsed = DiffView.parse("diff --git a/i.png b/i.png\nindex 1..2 100644\nBinary files a/i.png and b/i.png differ\n", 100);
        assertTrue(parsed.binary());
        assertTrue(parsed.rows().isEmpty());
    }

    @Test
    void truncatesRowsButCountsEverything() {
        var parsed = DiffView.parse("@@ -1,3 +1,3 @@\n+a\n+b\n+c\n", 2);
        assertEquals(3, parsed.rows().size());
        assertEquals("… diff truncated", parsed.rows().get(2).text());
        assertEquals(3, parsed.added());
    }
}
