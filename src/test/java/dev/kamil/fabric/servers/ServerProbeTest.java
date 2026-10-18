package dev.kamil.fabric.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ServerProbeTest {

    @Test
    void commandHasOptionsAliasAndScript() {
        var command = new ServerProbe(Path.of("/cache/fm")).command("hetzner");
        assertEquals("ssh", command.get(0));
        assertTrue(command.containsAll(java.util.List.of("BatchMode=yes", "ConnectTimeout=5",
                "ControlMaster=auto", "ControlPath=/cache/fm/ssh-%C", "ControlPersist=60")));
        var sep = command.indexOf("--");
        assertEquals("hetzner", command.get(sep + 1));
        assertEquals(sep + 3, command.size());
        var script = command.get(sep + 2);
        assertTrue(script.startsWith("docker compose ls --all --format json\necho '---'\n"));
        assertTrue(script.contains("~/.fleet-state/*/current"));
    }
}
