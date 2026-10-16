package dev.kamil.fabric.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecentProjectsTest {

    @TempDir
    Path tmp;

    private static final Account PERSONAL = Account.personal(Path.of(System.getProperty("user.home")));

    private Path dir(String name) throws IOException {
        return Files.createDirectories(tmp.resolve("projects").resolve(name));
    }

    private Database db() {
        return new Database(tmp.resolve("db/fabric")).migrate();
    }

    private RecentProjects recent() {
        return new RecentProjects(db(), tmp.resolve("cfg/recent"));
    }

    @Test
    void missingFileIsEmpty() {
        var r = recent().load();
        assertTrue(r.projects().isEmpty());
        assertTrue(r.lastTool().isEmpty());
    }

    @Test
    void rememberDedupesAndMovesToFront() throws IOException {
        var r = recent();
        var a = dir("a");
        var b = dir("b");
        r.remember(a, Tool.SHELL, PERSONAL);
        r.remember(b, Tool.SHELL, PERSONAL);
        r.remember(a, Tool.SHELL, PERSONAL);
        assertEquals(java.util.List.of(a, b), r.projects());
    }

    @Test
    void capsAtTen() throws IOException {
        var r = recent();
        var all = new ArrayList<Path>();
        for (int i = 0; i < 12; i++) {
            var d = dir("p" + i);
            all.add(d);
            r.remember(d, Tool.SHELL, PERSONAL);
        }
        assertEquals(10, r.projects().size());
        assertEquals(all.get(11), r.projects().get(0));
        assertEquals(10, recent().load().projects().size());
    }

    @Test
    void missingDirectoryDroppedOnLoad() throws IOException {
        var r = recent();
        var a = dir("a");
        var b = dir("b");
        r.remember(a, Tool.SHELL, PERSONAL);
        r.remember(b, Tool.SHELL, PERSONAL);
        Files.delete(b);
        assertEquals(java.util.List.of(a), recent().load().projects());
    }

    @Test
    void roundTripBuiltInTool() throws IOException {
        var a = dir("a");
        recent().remember(a, new Tool("codex", "codex"), PERSONAL);
        var loaded = recent().load();
        assertEquals(java.util.List.of(a), loaded.projects());
        assertEquals(new Tool("codex", "codex"), loaded.lastTool().orElseThrow());
    }

    @Test
    void roundTripCustomTool() throws IOException {
        var a = dir("a");
        recent().remember(a, Tool.custom("claude --resume"), PERSONAL);
        assertEquals(Tool.custom("claude --resume"), recent().load().lastTool().orElseThrow());
    }

    @Test
    void legacyFileImportedOnceKeepingOrderAndLastTool() throws IOException {
        var a = dir("a");
        var b = dir("b");
        var legacy = Files.createDirectories(tmp.resolve("cfg")).resolve("recent");
        Files.write(legacy, java.util.List.of("custom:claude --resume", a.toString(), b.toString()));
        var r = recent().load();
        assertEquals(java.util.List.of(a, b), r.projects());
        assertEquals(Tool.custom("claude --resume"), r.lastTool().orElseThrow());
        assertTrue(Files.exists(legacy.resolveSibling("recent.bak")));
        assertTrue(Files.notExists(legacy));
    }

    @Test
    void dataSurvivesNewInstanceOnSameDatabase() throws IOException {
        var db = db();
        var a = dir("a");
        new RecentProjects(db, tmp.resolve("cfg/recent")).remember(a, Tool.SHELL, PERSONAL);
        var loaded = new RecentProjects(db, tmp.resolve("cfg/recent")).load();
        assertEquals(java.util.List.of(a), loaded.projects());
        assertEquals(Tool.SHELL, loaded.lastTool().orElseThrow());
    }

    @Test
    void accountIsStoredAndDefaultsByFolder() throws IOException {
        var home = Files.createDirectories(tmp.resolve("home"));
        Files.createDirectories(home.resolve(".claude-work"));
        var r = new RecentProjects(db(), tmp.resolve("cfg/recent"), home);
        var work = Account.byName("work", home, Account.available(home));
        var amu = Files.createDirectories(home.resolve("Documents/Amu/x"));
        var other = Files.createDirectories(home.resolve("other"));
        assertEquals(work, r.accountFor(amu));
        assertEquals(Account.personal(home), r.accountFor(other));
        r.remember(amu, Tool.SHELL, Account.personal(home));
        r.remember(other, Tool.SHELL, work);
        assertEquals(Account.personal(home), r.accountFor(amu));
        assertEquals(work, r.accountFor(other));
    }
}
