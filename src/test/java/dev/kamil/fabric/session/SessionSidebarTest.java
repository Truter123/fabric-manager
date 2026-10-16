package dev.kamil.fabric.session;

import dev.kamil.fabric.terminal.TerminalTab;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SessionSidebarTest {

    private static final Path HOME = Path.of("/home/me");

    @Test
    void parentUnderHomeUsesTilde() {
        assertEquals("~/Documents/Tools", SessionSidebar.parentLabel(Path.of("/home/me/Documents/Tools/app"), HOME));
    }

    @Test
    void projectDirectlyInHome() {
        assertEquals("~", SessionSidebar.parentLabel(Path.of("/home/me/app"), HOME));
    }

    @Test
    void parentOutsideHomeStaysAbsolute() {
        assertEquals("/srv", SessionSidebar.parentLabel(Path.of("/srv/app"), HOME));
    }

    @Test
    void rootHasNoParent() {
        assertEquals("", SessionSidebar.parentLabel(Path.of("/"), HOME));
    }

    @Test
    void namedSessionShowsWholeFolder() {
        assertEquals("~/Documents/Tools/app", SessionSidebar.homeLabel(Path.of("/home/me/Documents/Tools/app"), HOME));
    }

    @Test
    void homeItselfIsTilde() {
        assertEquals("~", SessionSidebar.homeLabel(HOME, HOME));
    }

    @Test
    void activityTextMapping() {
        assertEquals("waiting for you", SessionSidebar.activityText(TerminalTab.Activity.WAITING));
        assertEquals("done", SessionSidebar.activityText(TerminalTab.Activity.DONE));
        assertEquals("error", SessionSidebar.activityText(TerminalTab.Activity.ERROR));
        org.junit.jupiter.api.Assertions.assertNull(SessionSidebar.activityText(TerminalTab.Activity.WORKING));
        org.junit.jupiter.api.Assertions.assertNull(SessionSidebar.activityText(null));
    }

    @Test
    void changedOnlyWhenTheSnapshotDiffers() {
        var a = java.util.Map.of("t", new SessionSidebar.Sample(TerminalTab.Status.SHELL, "main"));
        var same = java.util.Map.of("t", new SessionSidebar.Sample(TerminalTab.Status.SHELL, "main"));
        var status = java.util.Map.of("t", new SessionSidebar.Sample(TerminalTab.Status.TOOL_RUNNING, "main"));
        var branch = java.util.Map.of("t", new SessionSidebar.Sample(TerminalTab.Status.SHELL, null));
        org.junit.jupiter.api.Assertions.assertFalse(SessionSidebar.changed(a, same));
        org.junit.jupiter.api.Assertions.assertTrue(SessionSidebar.changed(a, status));
        org.junit.jupiter.api.Assertions.assertTrue(SessionSidebar.changed(a, branch));
        org.junit.jupiter.api.Assertions.assertTrue(SessionSidebar.changed(a, java.util.Map.of()));
    }
}
