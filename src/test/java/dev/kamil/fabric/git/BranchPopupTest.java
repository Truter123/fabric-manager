package dev.kamil.fabric.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BranchPopupTest {

    private static GitRepo.Branch local(String name, boolean current) {
        return new GitRepo.Branch(name, false, current, null, Instant.EPOCH);
    }

    private static GitRepo.Branch remote(String name) {
        return new GitRepo.Branch(name, true, false, null, Instant.EPOCH);
    }

    private static List<String> labels(List<BranchPopup.Row> rows) {
        return rows.stream().map(r -> r.isHeader() ? "#" + r.header() : r.branch().name()).toList();
    }

    private final List<GitRepo.Branch> all = List.of(
            local("main", true), local("feature", false), local("Fix-Bug", false), remote("origin/main"));

    @Test
    void groupsAreRecentLocalRemote() {
        var rows = BranchPopup.rows(all, List.of("feature"), "");
        assertEquals(List.of("#Recent", "feature", "#Local", "main", "feature", "Fix-Bug",
                "#Remote", "origin/main"), labels(rows));
    }

    @Test
    void recentExcludesCurrent() {
        var rows = BranchPopup.rows(all, List.of("main", "feature"), "");
        assertEquals(List.of("#Recent", "feature", "#Local"), labels(rows).subList(0, 3));
    }

    @Test
    void filterIsCaseInsensitive() {
        var rows = BranchPopup.rows(all, List.of(), "fIX");
        assertEquals(List.of("#Local", "Fix-Bug"), labels(rows));
    }

    @Test
    void emptyGroupsHaveNoHeader() {
        var rows = BranchPopup.rows(List.of(local("main", true)), List.of(), "");
        assertEquals(List.of("#Local", "main"), labels(rows));
    }

    @Test
    void remoteWithLocalTwinStillListed() {
        var rows = BranchPopup.rows(all, List.of(), "main");
        assertEquals(List.of("#Local", "main", "#Remote", "origin/main"), labels(rows));
    }
}
