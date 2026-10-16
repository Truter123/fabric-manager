package dev.kamil.fabric.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SidebarActionsTest {

    private static SessionSidebar.Action action(SessionSidebar.Group group, String label) {
        return new SessionSidebar.Action(group, "*", label, "keys", () -> {
        });
    }

    @Test
    void groupsFollowEnumOrderAndKeepInsertionOrderInside() {
        var grouped = SessionSidebar.byGroup(List.of(
                action(SessionSidebar.Group.FOOTER, "Settings"),
                action(SessionSidebar.Group.GRID, "Notes"),
                action(SessionSidebar.Group.PRIMARY, "New"),
                action(SessionSidebar.Group.GRID, "Tasks")));
        assertEquals(List.of(SessionSidebar.Group.PRIMARY, SessionSidebar.Group.GRID, SessionSidebar.Group.FOOTER),
                List.copyOf(grouped.keySet()));
        assertEquals(List.of("Notes", "Tasks"),
                grouped.get(SessionSidebar.Group.GRID).stream().map(SessionSidebar.Action::label).toList());
    }

    @Test
    void emptyGroupsAreOmitted() {
        assertFalse(SessionSidebar.byGroup(List.of(action(SessionSidebar.Group.GRID, "Notes")))
                .containsKey(SessionSidebar.Group.FOOTER));
    }

    @Test
    void toggleTextShowsTheChevronForTheState() {
        assertEquals("\u25B8 More", SessionSidebar.toggleText(true));
        assertEquals("\u25BE Less", SessionSidebar.toggleText(false));
    }

    @Test
    void toggleFlipsTheCollapsedState() {
        assertTrue(SessionSidebar.toggled(false));
        assertFalse(SessionSidebar.toggled(true));
    }

    @Test
    void usageHeaderShowsSummaryOnlyWhenCollapsed() {
        assertEquals("\u25B8 Usage  5h 2% · Week 43%", SessionSidebar.usageHeader(true, "5h 2% · Week 43%"));
        assertEquals("\u25BE Usage", SessionSidebar.usageHeader(false, "5h 2% · Week 43%"));
        assertEquals("\u25B8 Usage", SessionSidebar.usageHeader(true, ""));
    }

    @Test
    void fuseTargetsSkipTheSourceAndIneligibleTabs() {
        var a = "a";
        var b = "b";
        var dead = "dead";
        assertEquals(List.of(b), SessionSidebar.fuseTargets(List.of(a, b, dead), a, t -> !t.equals("dead")));
        assertTrue(SessionSidebar.fuseTargets(List.of(a), a, t -> true).isEmpty());
    }
}
