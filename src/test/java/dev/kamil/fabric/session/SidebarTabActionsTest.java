package dev.kamil.fabric.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.FxHarness;
import java.util.List;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The sidebar buttons that act on the selected session are disabled while no session is open. */
class SidebarTabActionsTest {

    @BeforeAll
    static void startFx() {
        Assumptions.assumeTrue(FxHarness.displayAvailable(),
                "no display");
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // another test class started it
        }
        Platform.setImplicitExit(false);
    }

    @Test
    void anActionThatNeedsASessionIsDisabledUntilOneIsOpen() throws Exception {
        var hasSession = new SimpleBooleanProperty(false);
        var needsSession = new SessionSidebar.Action(SessionSidebar.Group.GRID, "*", "Snippets", "keys", () -> { },
                hasSession);
        var always = new SessionSidebar.Action(SessionSidebar.Group.GRID, "*", "Notes", "keys", () -> { });
        var buttons = FxHarness.fx(() -> List.of(SessionSidebar.actionButton(needsSession, true),
                SessionSidebar.actionButton(always, true)));
        assertTrue(FxHarness.fx(() -> buttons.get(0).isDisable()));
        assertFalse(FxHarness.fx(() -> buttons.get(1).isDisable()));
        FxHarness.fx(() -> {
            hasSession.set(true);
            return null;
        });
        assertFalse(FxHarness.fx(() -> buttons.get(0).isDisable()));
        FxHarness.fx(() -> {
            hasSession.set(false);
            return null;
        });
        assertTrue(FxHarness.fx(() -> buttons.get(0).isDisable()));
    }
}
