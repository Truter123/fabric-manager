package dev.kamil.fabric.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SshConfigTest {

    @TempDir
    Path dir;

    private Path write(String name, String text) throws IOException {
        var file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    private static List<String> aliases(List<SshHost> hosts) {
        return hosts.stream().map(SshHost::alias).toList();
    }

    @Test
    void multipleAliasesShareBlockValues() throws IOException {
        var file = write("config", """
                Host web web2
                    HostName example.com
                    User deploy
                    Port 2222
                Host db
                    HostName db.internal
                """);
        var hosts = SshConfig.read(file);
        assertEquals(List.of("web", "web2", "db"), aliases(hosts));
        assertEquals(new SshHost("web2", "example.com", "deploy", 2222), hosts.get(1));
        assertEquals("deploy@example.com:2222", hosts.get(0).detail());
        assertEquals("db.internal", hosts.get(2).detail());
        assertNull(hosts.get(2).user());
    }

    @Test
    void wildcardsAndNegationsSkipped() throws IOException {
        var file = write("config", """
                Host * !bad foo? *.corp good
                    User x
                Host *
                    User y
                """);
        assertEquals(List.of("good"), aliases(SshConfig.read(file)));
    }

    @Test
    void includeWithGlobAndRelativePath() throws IOException {
        write("config.d/a.conf", "Host a\n  HostName a.example\n");
        write("config.d/b.conf", "Host b\n");
        write("other", "Host o\n");
        var file = write("config", "Include config.d/*\nInclude other\nHost main\n");
        assertEquals(List.of("a", "b", "o", "main"), aliases(SshConfig.read(file)));
    }

    @Test
    void includeCycleTerminates() throws IOException {
        write("one", "Host one\nInclude two\n");
        write("two", "Host two\nInclude one\n");
        assertEquals(List.of("one", "two"), aliases(SshConfig.read(dir.resolve("one"))));
    }

    @Test
    void missingFileGivesEmptyList() {
        assertEquals(List.of(), SshConfig.read(dir.resolve("nope")));
    }

    @Test
    void keywordsAreCaseInsensitiveAndAllowEqualsAndTabs() throws IOException {
        var file = write("config", "HOST=foo\n\thostname\tfoo.example\n\tUSER = bar\n\tPORT=22\nhost\tbaz\n");
        var hosts = SshConfig.read(file);
        assertEquals(List.of("foo", "baz"), aliases(hosts));
        assertEquals("bar@foo.example:22", hosts.get(0).detail());
    }

    @Test
    void duplicateAliasesKeepFirst() throws IOException {
        var file = write("config", "Host a\n HostName one\nHost a\n HostName two\n");
        var hosts = SshConfig.read(file);
        assertEquals(1, hosts.size());
        assertEquals("one", hosts.get(0).hostName());
    }
}
