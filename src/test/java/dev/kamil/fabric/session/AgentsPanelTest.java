package dev.kamil.fabric.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.kamil.fabric.terminal.TerminalTab.Activity;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentsPanelTest {

    private static AgentsPanel.Agent agent(String name, Activity activity) {
        return new AgentsPanel.Agent(name, name, "", null, activity, false, false);
    }

    @Test
    void headerSaysNoAgentsWhenEmpty() {
        assertEquals("No agents", AgentsPanel.header(List.of()));
    }

    @Test
    void headerHidesZeroParts() {
        assertEquals("2 working", AgentsPanel.header(List.of(agent("a", Activity.WORKING),
                agent("b", Activity.WORKING), agent("c", Activity.DONE))));
        assertEquals("1 working · 1 needs input", AgentsPanel.header(List.of(agent("a", Activity.WORKING),
                agent("b", Activity.WAITING))));
        assertEquals("1 needs input", AgentsPanel.header(List.of(agent("a", Activity.WAITING))));
    }

    @Test
    void sortsWaitingErrorWorkingThenRestByName() {
        var sorted = AgentsPanel.sorted(List.of(agent("d", Activity.DONE), agent("w2", Activity.WORKING),
                agent("e", Activity.ERROR), agent("w1", Activity.WORKING), agent("n", Activity.WAITING),
                agent("a", null)));
        assertEquals(List.of("n", "e", "w1", "w2", "a", "d"), sorted.stream().map(AgentsPanel.Agent::name).toList());
    }

    @Test
    void personalAccountIsHidden() {
        assertEquals("", AgentsPanel.accountLabel("personal"));
        assertEquals("work", AgentsPanel.accountLabel("work"));
    }

    @Test
    void detailListsProjectAndAccount() {
        var a = new AgentsPanel.Agent("fix login", "api", "work", null, null, false, false);
        assertEquals("api · work", AgentsPanel.detail(a));
    }

    @Test
    void claudeStatusLine() {
        var line = new AgentsPanel.Line(true, "working", "ctx 68k / 200k · 34%", "main", "work");
        assertEquals("working · ctx 34% · ⎇ main · work", AgentsPanel.statusLine(line));
    }

    @Test
    void claudeStatusLineOmitsUnknownParts() {
        assertEquals("idle", AgentsPanel.statusLine(new AgentsPanel.Line(true, "idle", null, null, "")));
    }

    @Test
    void shellStatusLine() {
        assertEquals("shell · ⎇ dev", AgentsPanel.statusLine(new AgentsPanel.Line(false, "x", null, "dev", "work")));
        assertEquals("shell", AgentsPanel.statusLine(new AgentsPanel.Line(false, "x", null, null, "")));
    }

    @Test
    void accountLabelPrefersTheConfiguredLabel() {
        var settings = dev.kamil.fabric.settings.AppSettings.inMemory();
        settings.setString("account.work.label", "Team");
        settings.setString("account.personal.label", "Max");
        var previous = dev.kamil.fabric.settings.AppSettings.shared();
        dev.kamil.fabric.settings.AppSettings.use(settings);
        try {
            assertEquals("Team", AgentsPanel.accountLabel("work"));
            assertEquals("Max", AgentsPanel.accountLabel("personal"));
            assertEquals("other", AgentsPanel.accountLabel("other"));
        } finally {
            dev.kamil.fabric.settings.AppSettings.use(previous);
        }
    }
}
