package dev.kamil.fabric.session;

import dev.kamil.fabric.git.GitRepo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class HistoryWindowTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private static String rel(String date) {
        return HistoryWindow.relativeDate(Instant.parse(date), NOW, UTC);
    }

    @Test
    void relativeDates() {
        assertEquals("just now", rel("2026-10-10T11:59:30Z"));
        assertEquals("just now", rel("2026-10-10T12:05:00Z"));
        assertEquals("5 min ago", rel("2026-10-10T11:55:00Z"));
        assertEquals("3 h ago", rel("2026-10-10T09:00:00Z"));
        assertEquals("yesterday", rel("2026-10-09T08:00:00Z"));
        assertEquals("4 days ago", rel("2026-10-06T08:00:00Z"));
        assertEquals("2026-09-01", rel("2026-09-01T08:00:00Z"));
    }

    @Test
    void diffLines() {
        assertEquals(HistoryWindow.DiffKind.HEADER, HistoryWindow.classifyDiffLine("diff --git a/x b/x", false));
        assertEquals(HistoryWindow.DiffKind.HEADER, HistoryWindow.classifyDiffLine("--- a/x", false));
        assertEquals(HistoryWindow.DiffKind.HEADER, HistoryWindow.classifyDiffLine("+++ b/x", false));
        assertEquals(HistoryWindow.DiffKind.HUNK, HistoryWindow.classifyDiffLine("@@ -1 +1 @@", false));
        assertEquals(HistoryWindow.DiffKind.ADD, HistoryWindow.classifyDiffLine("+a", true));
        assertEquals(HistoryWindow.DiffKind.REMOVE, HistoryWindow.classifyDiffLine("-a", true));
        assertEquals(HistoryWindow.DiffKind.REMOVE, HistoryWindow.classifyDiffLine("--- not a header", true));
        assertEquals(HistoryWindow.DiffKind.CONTEXT, HistoryWindow.classifyDiffLine(" a", true));
        assertEquals(HistoryWindow.DiffKind.HEADER, HistoryWindow.classifyDiffLine("diff --git a/y b/y", true));
    }

    @Test
    void refs() {
        assertEquals(HistoryWindow.RefKind.HEAD, HistoryWindow.classifyRef("HEAD -> main"));
        assertEquals(HistoryWindow.RefKind.HEAD, HistoryWindow.classifyRef("HEAD"));
        assertEquals(HistoryWindow.RefKind.LOCAL, HistoryWindow.classifyRef("main"));
        assertEquals(HistoryWindow.RefKind.REMOTE, HistoryWindow.classifyRef("origin/main"));
        assertEquals(HistoryWindow.RefKind.TAG, HistoryWindow.classifyRef("tag: v1.0"));
    }

    @Test
    void formatDates() {
        var zone = UTC;
        assertEquals("Today 09:05", HistoryWindow.formatDate(Instant.parse("2026-10-10T09:05:00Z"), NOW, zone));
        assertEquals("Yesterday 23:59", HistoryWindow.formatDate(Instant.parse("2026-10-09T23:59:00Z"), NOW, zone));
        assertEquals("08.10.26 08:01", HistoryWindow.formatDate(Instant.parse("2026-10-08T08:01:00Z"), NOW, zone));
    }

    @Test
    void chipsAreOrderedAndClassified() {
        var chips = HistoryWindow.chips(
                List.of("tag: v1", "origin/main", "feature/x", "HEAD -> main", "fork/dev"), Set.of("fork"));
        assertEquals(List.of(
                new HistoryWindow.Chip("HEAD", HistoryWindow.RefKind.HEAD),
                new HistoryWindow.Chip("main", HistoryWindow.RefKind.CURRENT),
                new HistoryWindow.Chip("feature/x", HistoryWindow.RefKind.LOCAL),
                new HistoryWindow.Chip("origin/main", HistoryWindow.RefKind.REMOTE),
                new HistoryWindow.Chip("fork/dev", HistoryWindow.RefKind.REMOTE),
                new HistoryWindow.Chip("tag: v1", HistoryWindow.RefKind.TAG)), chips);
        assertEquals(3, HistoryWindow.visibleChips(chips));
        assertEquals(2, HistoryWindow.visibleChips(HistoryWindow.chips(List.of("a", "b", "c"), Set.of())));
        assertEquals(List.of(new HistoryWindow.Chip("HEAD", HistoryWindow.RefKind.HEAD)),
                HistoryWindow.chips(List.of("HEAD"), Set.of()));
    }

    @Test
    void headCommitAndLaneColors() {
        var head = new GitRepo.Commit("a".repeat(40), "aaaaaaa", List.of(), "x", NOW, List.of("HEAD -> main"), "s");
        var other = new GitRepo.Commit("b".repeat(40), "bbbbbbb", List.of(), "x", NOW, List.of("main"), "s");
        assertTrue(HistoryWindow.isHead(head));
        assertFalse(HistoryWindow.isHead(other));
        assertEquals(HistoryWindow.laneColor(0), HistoryWindow.laneColor(8));
        assertEquals(HistoryWindow.laneColor(7), HistoryWindow.laneColor(-1));
        assertNotEquals(HistoryWindow.laneColor(0), HistoryWindow.laneColor(1));
    }

    @Test
    void branchesLineCapsAtTen() {
        assertEquals("In no branches", HistoryWindow.branchesLine(List.of()));
        assertEquals("In 1 branch: main", HistoryWindow.branchesLine(List.of("main")));
        var many = IntStream.rangeClosed(1, 12).mapToObj(i -> "b" + i).toList();
        assertEquals("In 12 branches: b1, b2, b3, b4, b5, b6, b7, b8, b9, b10 and 2 more", HistoryWindow.branchesLine(many));
    }

    @Test
    void metaLineShowsCommitterOnlyWhenDifferent() {
        var commit = new GitRepo.Commit("a".repeat(40), "aaaaaaa", List.of(), "Ann", Instant.parse("2026-10-10T10:00:00Z"), List.of(), "s");
        var same = new GitRepo.CommitInfo("ann@x.org", "Ann", Instant.parse("2026-10-10T11:00:00Z"), "");
        var other = new GitRepo.CommitInfo("ann@x.org", "Bob", Instant.parse("2026-10-10T11:00:00Z"), "");
        assertEquals("aaaaaaa Ann <ann@x.org> on 2026-10-10 10:00:00", HistoryWindow.metaLine(commit, same, UTC));
        assertEquals("aaaaaaa Ann <ann@x.org> on 2026-10-10 10:00:00\nCommitted by Bob on 2026-10-10 11:00:00",
                HistoryWindow.metaLine(commit, other, UTC));
    }

    @Test
    void longRefKeepsItsEnd() {
        assertEquals("origin/feature/search", HistoryWindow.shortRef("origin/feature/search"));
        assertEquals("…ture/a-very-long-branch", HistoryWindow.shortRef("origin/feature/a-very-long-branch"));
        assertEquals(24, HistoryWindow.shortRef("origin/feature/a-very-long-branch").length());
    }
}
