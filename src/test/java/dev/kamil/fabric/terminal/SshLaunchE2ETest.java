package dev.kamil.fabric.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.session.Tool;
import dev.kamil.fabric.session.Database;
import dev.kamil.fabric.ssh.SshConfig;
import dev.kamil.fabric.ssh.SshEntry;
import dev.kamil.fabric.ssh.SshHostStore;
import dev.kamil.fabric.ssh.SshHost;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Config file to host list to the command a tab runs. The last step (pty start) needs a display and a real ssh,
 * so it is not covered here; the highest seam without them is {@link TerminalTab#launchScript}.
 */
class SshLaunchE2ETest {

    @TempDir
    Path dir;

    private Path fixture() throws IOException {
        var ssh = Files.createDirectories(dir.resolve(".ssh"));
        Files.writeString(ssh.resolve("extra"), "Host extra-box\n    HostName 10.0.0.9\n    Port 2200\n");
        Files.writeString(ssh.resolve("config"), """
                # personal servers
                Include extra
                Host *
                    ServerAliveInterval 30

                Host hetzner deploy 178.105.206.130   # the box
                    HostName 178.105.206.130
                    User admin
                    IdentityFile ~/.ssh/key
                    IdentitiesOnly yes

                Host web-*
                    User nobody

                Host "quoted one"
                    Port=2222
                """);
        return ssh.resolve("config");
    }

    private static String script(SshHost host) {
        var tool = Tool.ssh(host.alias());
        assertTrue(tool.isSsh());
        return TerminalTab.launchScript(tool, tool.command(), "/bin/bash");
    }

    @Test
    void configBecomesHostListAndEachHostBecomesAnSshCommand() throws IOException {
        var hosts = SshConfig.read(fixture());
        assertEquals(List.of("extra-box", "hetzner", "deploy", "178.105.206.130", "quoted one"),
                hosts.stream().map(SshHost::alias).toList());

        var hetzner = hosts.get(1);
        assertEquals("admin@178.105.206.130", hetzner.detail());
        assertEquals("ssh -- 'hetzner'", script(hetzner));
        assertEquals("ssh -- 'quoted one'", script(hosts.get(4)));
        assertEquals(2200, hosts.get(0).port());
        assertEquals(2222, hosts.get(4).port());
    }

    @Test
    void aliasIsQuotedAgainstShellSyntaxAndOptions() {
        assertEquals("ssh -- 'a'\\''; rm -rf ~'", Tool.ssh("a'; rm -rf ~").command());
        assertEquals("ssh -- '-oProxyCommand=x'", Tool.ssh("-oProxyCommand=x").command());
    }

    @Test
    void appHostsMergeWithConfigHiddenHostIsNotListedAndAppHostLaunches() throws IOException {
        var config = SshConfig.read(fixture());
        var store = new SshHostStore(Database.at(dir.resolve("db/fabric")).migrate());
        store.add("mine", "my.example.com", "bob", 2022, "/home/bob/.ssh/id key");
        store.hide("deploy");

        var shown = SshEntry.merge(config, store.list(), store.hidden(), false);
        assertEquals(List.of("mine", "extra-box", "hetzner", "178.105.206.130", "quoted one"),
                shown.stream().map(SshEntry::alias).toList());
        assertEquals(List.of("deploy"), SshEntry.merge(config, store.list(), store.hidden(), true).stream()
                .filter(SshEntry::hidden).map(SshEntry::alias).toList());

        var app = shown.get(0).app();
        var tool = Tool.ssh(app.name(), app.hostName(), app.user(), app.port(), app.identity());
        assertTrue(tool.isSsh());
        assertEquals("ssh -p '2022' -i '/home/bob/.ssh/id key' -- 'bob@my.example.com'",
                TerminalTab.launchScript(tool, tool.command(), "/bin/bash"));
    }
}
