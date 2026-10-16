package dev.kamil.fabric.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;

class TerminalTabKeysTest {

    @Test
    void shiftEnterIsEscCr() {
        assertEquals("\u001b\r", TerminalTab.sequenceFor(KeyCode.ENTER, true, false, false, false));
    }

    @Test
    void shiftTabIsBackTab() {
        assertEquals("\u001b[Z", TerminalTab.sequenceFor(KeyCode.TAB, true, false, false, false));
    }

    @Test
    void plainTabIsTab() {
        assertEquals("\t", TerminalTab.sequenceFor(KeyCode.TAB, false, false, false, false));
    }

    @Test
    void ctrlShiftTabIsAppAccelerator() {
        assertNull(TerminalTab.sequenceFor(KeyCode.TAB, true, true, false, false));
    }

    @Test
    void plainEnterIsNotHandled() {
        assertNull(TerminalTab.sequenceFor(KeyCode.ENTER, false, false, false, false));
    }

    @Test
    void blankNameKeepsDefaultTitle() {
        assertEquals("claude: nlp", TerminalTab.titleFor("  ", "claude: nlp"));
    }

    @Test
    void nameReplacesDefaultTitle() {
        assertEquals("shop api", TerminalTab.titleFor("shop api", "claude: nlp"));
    }
}
