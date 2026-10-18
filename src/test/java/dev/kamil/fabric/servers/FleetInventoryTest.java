package dev.kamil.fabric.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FleetInventoryTest {

    @TempDir
    Path root;

    private void env(String project, String content) throws IOException {
        var dir = Files.createDirectories(root.resolve("projects").resolve(project));
        Files.writeString(dir.resolve("fleet.env"), content);
    }

    @Test
    void parsesQuotesAndComments() throws IOException {
        env("zeta", "# comment\nSSH_HOST=\"box\"  # trailing\nREMOTE_DIR='z-dir'\nREMOTE_DIR_TEST=\"z-test\"\nOTHER=\"x y\"\n");
        var servers = FleetInventory.read(root);
        assertEquals(1, servers.size());
        assertEquals("box", servers.get(0).alias());
        assertEquals(List.of(new FleetInventory.ExpectedApp("zeta", "prod", "z-dir"),
                new FleetInventory.ExpectedApp("zeta", "test", "z-test")), servers.get(0).apps());
    }

    @Test
    void skipsProjectWithoutSshHost() throws IOException {
        env("dj", "REMOTE_DIR=\"dj\"\n");
        env("a", "SSH_HOST=\"\"\nREMOTE_DIR=\"a\"\n");
        assertTrue(FleetInventory.read(root).isEmpty());
    }

    @Test
    void missingRemoteDirTestGivesOnlyProd() throws IOException {
        env("solo", "SSH_HOST=\"box\"\nREMOTE_DIR=\"solo-dir\"\n");
        var apps = FleetInventory.read(root).get(0).apps();
        assertEquals(List.of(new FleetInventory.ExpectedApp("solo", "prod", "solo-dir")), apps);
    }

    @Test
    void sortsServersAndApps() throws IOException {
        env("b", "SSH_HOST=\"two\"\nREMOTE_DIR=\"b\"\nREMOTE_DIR_TEST=\"bt\"\n");
        env("a", "SSH_HOST=\"two\"\nREMOTE_DIR=\"a\"\n");
        env("c", "SSH_HOST=\"one\"\nREMOTE_DIR=\"c\"\n");
        var servers = FleetInventory.read(root);
        assertEquals(List.of("one", "two"), servers.stream().map(FleetInventory.Server::alias).toList());
        assertEquals(List.of("a/prod", "b/prod", "b/test"),
                servers.get(1).apps().stream().map(a -> a.project() + "/" + a.env()).toList());
    }

    @Test
    void missingDirectoryIsEmpty() {
        assertTrue(FleetInventory.read(root.resolve("nope")).isEmpty());
    }

    @Test
    void realDeploymentCenter() {
        Assumptions.assumeTrue(Files.isDirectory(FleetInventory.DEFAULT_PATH.resolve("projects")));
        var servers = FleetInventory.read(FleetInventory.DEFAULT_PATH);
        assertEquals(List.of("hetzner", "nlp"), servers.stream().map(FleetInventory.Server::alias).toList());
        servers.forEach(s -> assertEquals(2, s.apps().size()));
    }
}
