package dev.kamil.fabric.processes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DockerTest {

    @Test
    void parsesRunningAndExitedContainers() {
        var list = Docker.parse(List.of(
                "abc123|web|nginx:1|running|Up 2 hours|0.0.0.0:8080->80/tcp",
                "def456|db|postgres:16|exited|Exited (0) 3 days ago|"));

        assertEquals(2, list.size());
        var web = list.get(0);
        assertEquals("abc123", web.id());
        assertEquals("web", web.name());
        assertEquals("nginx:1", web.image());
        assertEquals("Up 2 hours", web.status());
        assertEquals("0.0.0.0:8080->80/tcp", web.ports());
        assertTrue(web.running());
        var db = list.get(1);
        assertFalse(db.running());
        assertEquals("", db.ports());
        assertEquals("Exited (0) 3 days ago", db.status());
    }

    @Test
    void skipsBlankAndMalformedLines() {
        assertEquals(0, Docker.parse(List.of("", "garbage")).size());
    }
}
