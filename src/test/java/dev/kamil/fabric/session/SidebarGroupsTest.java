package dev.kamil.fabric.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SidebarGroupsTest {

    private record S(String id, String dir, boolean pinned) {
    }

    private static SidebarGroups.Result<S> group(S... sessions) {
        return SidebarGroups.group(List.of(sessions), s -> Path.of(s.dir()), S::pinned);
    }

    @Test
    void emptyGivesNothing() {
        var result = group();
        assertTrue(result.pinned().isEmpty());
        assertTrue(result.projects().isEmpty());
    }

    @Test
    void groupsByFolderInFirstSeenOrder() {
        var result = group(new S("1", "/p/b", false), new S("2", "/p/a", false), new S("3", "/p/b", false));
        assertEquals(List.of("b", "a"), result.projects().stream().map(SidebarGroups.Group::name).toList());
        assertEquals(List.of("1", "3"), result.projects().get(0).sessions().stream().map(S::id).toList());
    }

    @Test
    void pinnedAreListedOnceAndLeaveTheirProject() {
        var result = group(new S("1", "/p/a", true), new S("2", "/p/a", false), new S("3", "/p/c", true));
        assertEquals(List.of("1", "3"), result.pinned().stream().map(S::id).toList());
        assertEquals(1, result.projects().size());
        assertEquals(List.of("2"), result.projects().get(0).sessions().stream().map(S::id).toList());
    }

    @Test
    void rootFolderNameIsThePath() {
        assertEquals("/", group(new S("1", "/", false)).projects().get(0).name());
    }
}
