package dev.kamil.fabric.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.kamil.fabric.session.Tool;
import org.junit.jupiter.api.Test;

class LaunchScriptTest {

    @Test
    void sshHasNoShellSuffix() {
        var tool = new Tool("ssh prod", "ssh 'prod'");
        assertEquals("ssh 'prod'", TerminalTab.launchScript(tool, tool.command(), "/bin/bash"));
    }

    @Test
    void otherToolsFallBackToLoginShell() {
        var claude = new Tool("claude", "claude");
        assertEquals("claude; exec /bin/zsh --login", TerminalTab.launchScript(claude, "claude", "/bin/zsh"));
        var custom = new Tool("custom", "htop");
        assertEquals("htop; exec /bin/bash --login", TerminalTab.launchScript(custom, "htop", "/bin/bash"));
    }
}
