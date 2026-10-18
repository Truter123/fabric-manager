package dev.kamil.fabric;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** The JavaFX tests run on the headless glass platform, so no window opens on the desktop. */
class HeadlessPlatformTest {

    @Test
    void javaFxRunsOnTheHeadlessGlassPlatform() {
        Assumptions.assumeTrue(FxHarness.headless(), "windows are shown (-PshowWindows): the real display is used");
        FxHarness.start();
        var application = com.sun.glass.ui.Application.GetApplication().getClass().getName();
        assertTrue(application.toLowerCase().contains("headless"), application);
    }
}
