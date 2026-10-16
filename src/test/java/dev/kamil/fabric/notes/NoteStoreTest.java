package dev.kamil.fabric.notes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.session.Database;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import liquibase.Scope;
import liquibase.command.CommandScope;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NoteStoreTest {

    @TempDir
    Path tmp;

    private NoteStore store() {
        return new NoteStore(Database.at(tmp.resolve("db/fabric")).migrate());
    }

    @Test
    void recentReturnsNewestFirstWithoutCorrection() {
        var store = store();
        long first = store.add("first");
        long second = store.add("second");

        var notes = store.recent(10);

        assertEquals(2, notes.size());
        assertEquals(second, notes.get(0).id());
        assertEquals("second", notes.get(0).text());
        assertEquals(first, notes.get(1).id());
        assertNull(notes.get(0).corrected());
    }

    @Test
    void correctedIsShownInRecent() {
        var store = store();
        long id = store.add("me go home");

        store.corrected(id, "X");

        assertEquals("X", store.recent(10).get(0).corrected());
    }

    @Test
    void migrationKeepsOldRows() throws Exception {
        var file = tmp.resolve("old/fabric");
        var db = Database.at(file);
        System.setProperty("liquibase.analytics.enabled", "false");
        Scope.child(Scope.Attr.resourceAccessor.name(), new ClassLoaderResourceAccessor(), () ->
                new CommandScope("updateCount")
                        .addArgumentValue("changelogFile", "db/changelog.yaml")
                        .addArgumentValue("url", "jdbc:h2:file:" + file.toAbsolutePath() + ";AUTO_SERVER=TRUE")
                        .addArgumentValue("username", "sa")
                        .addArgumentValue("password", "")
                        .addArgumentValue("count", 4)
                        .execute());
        var created = Timestamp.from(Instant.parse("2024-01-02T03:04:05Z"));
        try (var con = db.connect();
                var ps = con.prepareStatement("INSERT INTO note (text, corrected, created_at) VALUES (?, ?, ?)")) {
            ps.setString(1, "old #idea");
            ps.setString(2, "Old idea");
            ps.setTimestamp(3, created);
            ps.executeUpdate();
        }

        var notes = new NoteStore(db.migrate()).recent(10);

        assertEquals(1, notes.size());
        assertEquals("old #idea", notes.get(0).text());
        assertEquals("Old idea", notes.get(0).corrected());
        assertEquals(created.toInstant(), notes.get(0).updatedAt());
        assertFalse(notes.get(0).pinned());
        assertFalse(notes.get(0).inbox());
        assertNull(notes.get(0).title());
    }

    @Test
    void createUpdateDelete() {
        var store = store();
        long id = store.create("Title", "body #one", false);

        assertEquals("Title", store.find(id).title());
        assertEquals("one", store.find(id).tags());

        store.update(id, "", "other #Two #two");

        var note = store.find(id);
        assertNull(note.title());
        assertEquals("other #Two #two", note.text());
        assertEquals("two", note.tags());

        store.delete(id);
        assertNull(store.find(id));
    }

    @Test
    void displayTitleFallsBackToFirstLine() {
        var store = store();
        long id = store.add("\nFirst line\nsecond line");

        var note = store.find(id);

        assertEquals("First line", note.displayTitle());
        assertEquals("second line", note.preview());
    }

    @Test
    void searchMatchesTitleTextAndTagsIgnoringCase() {
        var store = store();
        long a = store.create("Groceries", "milk", false);
        long b = store.create(null, "call the DENTIST", false);
        long c = store.create(null, "plain #Errand", false);

        assertEquals(List.of(a), ids(store.search("grocer")));
        assertEquals(List.of(b), ids(store.search("dentist")));
        assertEquals(List.of(c), ids(store.search("ERRAND")));
        assertTrue(store.search("100%").isEmpty());
    }

    @Test
    void byTagMatchesWholeTagOnly() {
        var store = store();
        long a = store.add("x #work");
        store.add("y #workout");

        assertEquals(List.of(a), ids(store.byTag("work")));
        assertEquals(List.of("work", "workout"), store.allTags());
    }

    @Test
    void pinnedNotesComeFirst() {
        var store = store();
        long old = store.add("old");
        long mid = store.add("mid");
        long newest = store.add("newest");

        store.setPinned(old, true);

        assertEquals(List.of(old, newest, mid), ids(store.recent(10)));
        assertEquals(List.of(old), ids(store.pinned()));
    }

    @Test
    void captureLandsInInboxAndUpdateLeavesIt() {
        var store = store();
        long id = store.capture("quick thought");
        store.add("regular");

        assertEquals(List.of(id), ids(store.inbox()));

        store.update(id, null, "quick thought, sorted");

        assertTrue(store.inbox().isEmpty());
    }

    @Test
    void projectLinkAndUnlink() throws Exception {
        var db = Database.at(tmp.resolve("db/fabric")).migrate();
        try (var con = db.connect(); var st = con.createStatement()) {
            st.executeUpdate("INSERT INTO project (path, last_opened) VALUES ('/work/app', CURRENT_TIMESTAMP)");
        }
        var store = new NoteStore(db);
        long id = store.add("linked");

        store.setProject(id, "/work/app");
        assertEquals(List.of(id), ids(store.byProject("/work/app")));

        try (var con = db.connect(); var st = con.createStatement()) {
            st.executeUpdate("DELETE FROM project WHERE path = '/work/app'");
        }
        assertNull(store.find(id).projectPath());
    }

    @Test
    void restoreBringsBackADeletedNote() {
        var store = store();
        long id = store.create("T", "body #x", false);
        store.setPinned(id, true);
        var before = store.find(id);
        store.delete(id);

        long back = store.restore(before);

        var note = store.find(back);
        assertEquals("T", note.title());
        assertEquals("x", note.tags());
        assertTrue(note.pinned());
        assertEquals(before.createdAt(), note.createdAt());
    }

    private static List<Long> ids(List<Note> notes) {
        return notes.stream().map(Note::id).toList();
    }
}
