package dev.kamil.fabric.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionStoreTest {

    @TempDir
    Path tmp;

    private SessionStore store() {
        return new SessionStore(new Database(tmp.resolve("db/fabric")).migrate());
    }

    private static final Tool CLAUDE = new Tool("claude", "claude");
    private static final Account PERSONAL = Account.personal(java.nio.file.Path.of(System.getProperty("user.home")));

    @Test
    void startedThenRenamedAndEnded() throws Exception {
        var s = store();
        var p = tmp.resolve("a");
        s.started("id1", p, CLAUDE, PERSONAL, "claude: a");
        s.renamed("id1", "my work");
        var open = s.recent(10).get(0);
        assertEquals("my work", open.title());
        assertEquals(p, open.project());
        assertEquals(CLAUDE, open.tool());
        assertNull(open.endedAt());
        s.ended("id1");
        assertNotNull(s.recent(10).get(0).endedAt());
    }

    @Test
    void customToolRoundTrips() {
        var s = store();
        s.started("c", tmp, Tool.custom("claude --resume"), PERSONAL, "t");
        assertEquals(Tool.custom("claude --resume"), s.recent(1).get(0).tool());
    }

    @Test
    void recentIsNewestFirstAndFiltersByProject() throws Exception {
        var s = store();
        var a = tmp.resolve("a");
        var b = tmp.resolve("b");
        s.started("1", a, CLAUDE, PERSONAL, "one");
        Thread.sleep(5);
        s.started("2", b, CLAUDE, PERSONAL, "two");
        Thread.sleep(5);
        s.started("3", a, CLAUDE, PERSONAL, "three");
        assertEquals(java.util.List.of("3", "2", "1"), s.recent(10).stream().map(SavedSession::id).toList());
        assertEquals(java.util.List.of("3", "1"), s.recent(a, 10).stream().map(SavedSession::id).toList());
        assertEquals(1, s.recent(1).size());
    }

    @Test
    void restartingSameIdClearsEndAndMovesToTop() throws Exception {
        var s = store();
        s.started("1", tmp, CLAUDE, PERSONAL, "one");
        s.ended("1");
        Thread.sleep(5);
        s.started("2", tmp, CLAUDE, PERSONAL, "two");
        Thread.sleep(5);
        s.started("1", tmp, CLAUDE, PERSONAL, "one");
        var top = s.recent(10).get(0);
        assertEquals("1", top.id());
        assertNull(top.endedAt());
        assertEquals(2, s.recent(10).size());
    }

    @Test
    void statusIsStoredAndRepeatedWriteKeepsValue() {
        var s = store();
        s.started("1", tmp, CLAUDE, PERSONAL, "one");
        s.status("1", "WORKING");
        s.status("1", "WORKING");
        assertEquals("WORKING", s.recent(1).get(0).lastStatus());
        s.status("1", "DONE");
        assertEquals("DONE", s.recent(1).get(0).lastStatus());
    }

    @Test
    void accountRoundTripsAndUnknownFallsBackToPersonal() throws Exception {
        var home = java.nio.file.Files.createDirectories(tmp.resolve("home"));
        java.nio.file.Files.createDirectories(home.resolve(".claude-work"));
        var s = new SessionStore(new Database(tmp.resolve("db/fabric")).migrate(), home);
        var work = Account.byName("work", home, Account.available(home));
        s.started("w", tmp, CLAUDE, work, "t");
        assertEquals(work, s.recent(1).get(0).account());
        s.started("g", tmp, CLAUDE, new Account("gone", home.resolve(".claude-gone")), "t");
        assertEquals(Account.personal(home), s.recent(2).get(0).account());
    }

    @Test
    void pinnedDefaultsToFalseAndRoundTrips() {
        var s = store();
        s.started("1", tmp, CLAUDE, PERSONAL, "one");
        assertFalse(s.isPinned("1"));
        s.setPinned("1", true);
        assertTrue(s.isPinned("1"));
        s.started("1", tmp, CLAUDE, PERSONAL, "one");
        assertTrue(s.isPinned("1"));
        s.setPinned("1", false);
        assertFalse(s.isPinned("1"));
    }

    @Test
    void deleteRemovesSessionsButKeepsPinned() throws Exception {
        var s = store();
        var a = tmp.resolve("a");
        s.started("1", a, CLAUDE, PERSONAL, "one");
        s.started("2", a, CLAUDE, PERSONAL, "two");
        s.setPinned("2", true);
        s.delete(java.util.List.of("1", "2"));
        assertEquals(java.util.List.of("2"), s.recent(10).stream().map(SavedSession::id).toList());
    }
}
