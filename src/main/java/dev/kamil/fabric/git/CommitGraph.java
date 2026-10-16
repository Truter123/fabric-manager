package dev.kamil.fabric.git;

import java.util.ArrayList;
import java.util.List;

/** Lane layout for a commit graph, built page by page (commits newest first, children before parents). */
public final class CommitGraph {

    /**
     * One line segment inside a row. upper = from the row's top edge at fromLane to the row's centre at toLane;
     * otherwise from the centre at fromLane to the bottom edge at toLane. color = palette index (>= 0).
     */
    public record Line(int fromLane, int toLane, int color, boolean upper) {
    }

    /** lane/color of the commit dot; lines to draw in this row; laneCount = max lanes used in this row (for width). */
    public record Row(int lane, int color, List<Line> lines, int laneCount) {
    }

    private record Slot(String expected, int color) {
    }

    private List<Slot> active = new ArrayList<>();
    private int nextColor;

    /** Lays out the next page; state carries over, so appending page 2 continues the lines of page 1. */
    public List<Row> append(List<GitRepo.Commit> page) {
        var rows = new ArrayList<Row>(page.size());
        for (var commit : page) {
            rows.add(layout(commit));
        }
        return rows;
    }

    private Row layout(GitRepo.Commit commit) {
        var hash = commit.hash();
        var parents = commit.parents();
        var before = active.size();

        var commitLane = -1;
        for (var i = 0; i < before; i++) {
            var slot = active.get(i);
            if (slot != null && slot.expected().equals(hash)) {
                commitLane = i;
                break;
            }
        }
        var lines = new ArrayList<Line>();
        var next = new ArrayList<>(active);
        int commitColor;
        if (commitLane >= 0) {
            commitColor = active.get(commitLane).color();
        } else {
            commitColor = nextColor++;
            commitLane = firstEmpty(next);
        }

        // upper half
        for (var j = 0; j < before; j++) {
            var slot = active.get(j);
            if (slot == null) {
                continue;
            }
            if (slot.expected().equals(hash)) {
                lines.add(new Line(j, commitLane, slot.color(), true));
                if (j != commitLane) {
                    next.set(j, null);
                }
            } else {
                lines.add(new Line(j, j, slot.color(), true));
            }
        }

        // next state
        next.set(commitLane, parents.isEmpty() ? null : new Slot(parents.get(0), commitColor));
        var fresh = new boolean[Math.max(before, commitLane + 1) + parents.size() + 1];
        var targets = new ArrayList<Integer>();
        for (var k = 1; k < parents.size(); k++) {
            var p = parents.get(k);
            var target = -1;
            for (var j = 0; j < next.size(); j++) {
                var slot = next.get(j);
                if (j != commitLane && slot != null && slot.expected().equals(p)) {
                    target = j;
                    break;
                }
            }
            if (target < 0) {
                target = firstEmpty(next);
                next.set(target, new Slot(p, nextColor++));
                fresh[target] = true;
            }
            targets.add(target);
        }

        // compaction
        var map = new int[next.size()];
        var survivors = new ArrayList<Slot>();
        for (var j = 0; j < next.size(); j++) {
            if (next.get(j) != null) {
                map[j] = survivors.size();
                survivors.add(next.get(j));
            }
        }

        // lower half
        for (var j = 0; j < next.size(); j++) {
            var slot = next.get(j);
            if (slot != null && j != commitLane && !fresh[j]) {
                lines.add(new Line(j, map[j], slot.color(), false));
            }
        }
        if (!parents.isEmpty()) {
            lines.add(new Line(commitLane, map[commitLane], commitColor, false));
        }
        for (var t : targets) {
            lines.add(new Line(commitLane, map[t], next.get(t).color(), false));
        }

        var laneCount = Math.max(Math.max(before, survivors.size()), commitLane + 1);
        active = survivors;
        return new Row(commitLane, commitColor, List.copyOf(lines), laneCount);
    }

    private static int firstEmpty(List<Slot> slots) {
        for (var i = 0; i < slots.size(); i++) {
            if (slots.get(i) == null) {
                return i;
            }
        }
        slots.add(null);
        return slots.size() - 1;
    }
}
