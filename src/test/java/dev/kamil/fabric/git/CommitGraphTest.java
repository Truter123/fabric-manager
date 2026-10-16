package dev.kamil.fabric.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommitGraphTest {

    private static GitRepo.Commit commit(String hash, String... parents) {
        return new GitRepo.Commit(hash, hash, List.of(parents), "a", Instant.EPOCH, List.of(), hash);
    }

    private static boolean has(CommitGraph.Row row, int from, int to, boolean upper) {
        return row.lines().stream().anyMatch(l -> l.fromLane() == from && l.toLane() == to && l.upper() == upper);
    }

    @Test
    void linearHistoryUsesOneLane() {
        var rows = new CommitGraph().append(List.of(commit("c", "b"), commit("b", "a"), commit("a")));
        for (var row : rows) {
            assertEquals(0, row.lane());
            assertEquals(1, row.laneCount());
            assertEquals(rows.get(0).color(), row.color());
            assertTrue(row.lines().stream().allMatch(l -> l.fromLane() == 0 && l.toLane() == 0));
        }
        assertEquals(List.of(new CommitGraph.Line(0, 0, 0, false)), rows.get(0).lines());
        assertEquals(List.of(new CommitGraph.Line(0, 0, 0, true), new CommitGraph.Line(0, 0, 0, false)),
                rows.get(1).lines());
        assertEquals(List.of(new CommitGraph.Line(0, 0, 0, true)), rows.get(2).lines());
    }

    @Test
    void branchAndMerge() {
        var rows = new CommitGraph().append(List.of(
                commit("M", "A", "B"), commit("A", "R"), commit("B", "R"), commit("R")));
        var m = rows.get(0);
        assertEquals(0, m.lane());
        assertEquals(2, m.laneCount());
        assertTrue(has(m, 0, 0, false));
        assertTrue(has(m, 0, 1, false));
        var b = rows.get(2);
        assertEquals(1, b.lane());
        assertNotEquals(m.color(), b.color());
        var r = rows.get(3);
        assertEquals(0, r.lane());
        assertEquals(2, r.laneCount());
        assertTrue(has(r, 0, 0, true));
        assertTrue(has(r, 1, 0, true));
        assertEquals(2, r.lines().size());
    }

    @Test
    void secondParentAlreadyExpectedConnectsToThatLane() {
        var rows = new CommitGraph().append(List.of(commit("X", "A"), commit("M", "Q", "A")));
        var m = rows.get(1);
        assertEquals(1, m.lane());
        assertEquals(2, m.laneCount());
        assertTrue(has(m, 0, 0, true));
        assertTrue(has(m, 0, 0, false));
        assertTrue(has(m, 1, 1, false));
        assertTrue(has(m, 1, 0, false));
        assertEquals(4, m.lines().size());
    }

    @Test
    void octopusOpensOneLanePerParent() {
        var rows = new CommitGraph().append(List.of(commit("M", "A", "B", "C")));
        var m = rows.get(0);
        assertEquals(3, m.laneCount());
        assertTrue(has(m, 0, 0, false));
        assertTrue(has(m, 0, 1, false));
        assertTrue(has(m, 0, 2, false));
    }

    @Test
    void lanesCompactAfterAClose() {
        var rows = new CommitGraph().append(List.of(
                commit("X", "A"), commit("Y", "B"), commit("W", "C"), commit("A")));
        var a = rows.get(3);
        assertEquals(0, a.lane());
        assertEquals(3, a.laneCount());
        assertTrue(has(a, 0, 0, true));
        assertTrue(has(a, 1, 0, false));
        assertTrue(has(a, 2, 1, false));
        assertEquals(1, rows.get(0).lane() + 1);
    }

    @Test
    void convergingBranchesMergeAtTheirParent() {
        var rows = new CommitGraph().append(List.of(commit("X", "P"), commit("Y", "P"), commit("P")));
        assertEquals(1, rows.get(1).lane());
        var p = rows.get(2);
        assertEquals(0, p.lane());
        assertTrue(has(p, 0, 0, true));
        assertTrue(has(p, 1, 0, true));
    }

    @Test
    void pagingEqualsOnePass() {
        var all = List.of(commit("M", "A", "B"), commit("A", "R"), commit("B", "R"), commit("R", "Z"),
                commit("Z"));
        var whole = new CommitGraph().append(all);
        var graph = new CommitGraph();
        var paged = new ArrayList<>(graph.append(all.subList(0, 2)));
        paged.addAll(graph.append(all.subList(2, 5)));
        assertEquals(whole, paged);
    }

    @Test
    void rootCommitClosesItsLane() {
        var rows = new CommitGraph().append(List.of(commit("B", "A"), commit("A"), commit("Y")));
        assertEquals(List.of(new CommitGraph.Line(0, 0, 0, true)), rows.get(1).lines());
        assertEquals(0, rows.get(2).lane());
        assertEquals(1, rows.get(2).laneCount());
    }

    @Test
    void independentTipsGetSeparateLanes() {
        var rows = new CommitGraph().append(List.of(commit("T1", "A"), commit("T2", "B")));
        assertEquals(0, rows.get(0).lane());
        assertEquals(1, rows.get(1).lane());
        assertNotEquals(rows.get(0).color(), rows.get(1).color());
        assertTrue(has(rows.get(1), 0, 0, true));
        assertEquals(2, rows.get(1).laneCount());
    }
}
