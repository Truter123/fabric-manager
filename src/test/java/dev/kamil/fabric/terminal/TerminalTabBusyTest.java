package dev.kamil.fabric.terminal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A shell tab counts as running while its shell has a live child (a dev server, vim, ssh). */
class TerminalTabBusyTest {

    @Test
    void aProcessWithALiveChildIsBusyAndOneWithoutIsNot() throws Exception {
        var shell = new ProcessBuilder("sh", "-c", "sleep 30; true").start();
        try {
            var end = System.nanoTime() + 10_000_000_000L;
            while (!TerminalTab.hasChildren(shell.toHandle()) && System.nanoTime() < end) {
                Thread.sleep(25);
            }
            assertTrue(TerminalTab.hasChildren(shell.toHandle()));
            var child = shell.children().findFirst().orElseThrow();
            assertFalse(TerminalTab.hasChildren(child));
        } finally {
            shell.descendants().forEach(ProcessHandle::destroyForcibly);
            shell.destroyForcibly();
        }
    }
}
