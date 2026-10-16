package dev.kamil.fabric.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.kamil.fabric.session.SessionSidebar;
import dev.kamil.fabric.terminal.TerminalTab.Activity;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

class TabActivityTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void neverPrintedHasNoActivity() {
        assertNull(TerminalTab.outputActivity(TerminalTab.NEVER, 100 * SECOND));
    }

    @Test
    void outputOneSecondAgoIsWorking() {
        assertEquals(Activity.WORKING, TerminalTab.outputActivity(10 * SECOND, 11 * SECOND));
    }

    @Test
    void outputThreeSecondsAgoIsIdle() {
        assertEquals(Activity.DONE, TerminalTab.outputActivity(10 * SECOND, 13 * SECOND));
    }

    @Test
    void dotFollowsSidebarColourWhileRunning() {
        for (var activity : new Activity[]{null, Activity.WORKING, Activity.WAITING, Activity.DONE, Activity.ERROR}) {
            assertEquals(SessionSidebar.runningColor(activity), TerminalTab.dotColor(activity, false));
        }
    }

    @Test
    void exitedDotIsRedWhateverTheActivity() {
        var red = Color.web("#d96b75");
        assertEquals(red, TerminalTab.dotColor(null, true));
        assertEquals(red, TerminalTab.dotColor(Activity.WORKING, true));
    }
}
