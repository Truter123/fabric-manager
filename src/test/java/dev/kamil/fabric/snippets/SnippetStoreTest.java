package dev.kamil.fabric.snippets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.session.Database;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnippetStoreTest {

    @TempDir
    Path tmp;

    Database db;
    SnippetStore store;

    @BeforeEach
    void setUp() {
        db = Database.at(tmp.resolve("db/fabric")).migrate();
        store = new SnippetStore(db);
    }

    private void project(String path) throws Exception {
        try (var con = db.connect();
                var ps = con.prepareStatement("INSERT INTO project (path, last_opened) VALUES (?, ?)")) {
            ps.setString(1, path);
            ps.setTimestamp(2, java.sql.Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    private List<String> names(String project) {
        return store.list(project).stream().map(Snippet::name).toList();
    }

    @Test
    void listIsOrderedByLastUsedThenName() {
        long b = store.add("b", "text", null);
        store.add("c", "text", null);
        long a = store.add("a", "text", null);
        store.markUsed(b, Instant.parse("2026-01-01T00:00:00Z"));
        store.markUsed(a, Instant.parse("2026-02-01T00:00:00Z"));

        assertEquals(List.of("a", "b", "c"), names(null));
        store.markUsed(b, Instant.parse("2026-03-01T00:00:00Z"));
        assertEquals(List.of("b", "a", "c"), names(null));
        assertEquals(2, store.list(null).get(0).useCount());
    }

    @Test
    void unusedAreOrderedByName() {
        store.add("zeta", "t", null);
        store.add("alpha", "t", null);

        assertEquals(List.of("alpha", "zeta"), names(null));
    }

    @Test
    void listIncludesGlobalAndOwnProjectOnly() throws Exception {
        project("/p1");
        project("/p2");
        store.add("global", "t", null);
        store.add("one", "t", "/p1");
        store.add("two", "t", "/p2");

        assertEquals(List.of("global", "one"), names("/p1"));
        assertEquals(List.of("global"), names(null));
    }

    @Test
    void deletingProjectCascadesToItsSnippets() throws Exception {
        project("/p1");
        store.add("one", "t", "/p1");
        store.add("global", "t", null);

        try (var con = db.connect(); var st = con.createStatement()) {
            st.executeUpdate("DELETE FROM project WHERE path = '/p1'");
        }

        assertEquals(List.of("global"), names("/p1"));
    }

    @Test
    void updateAndDelete() {
        long id = store.add("old", "one\ntwo", null);
        store.update(id, "new", "changed", null);

        var snippet = store.list(null).get(0);
        assertEquals("new", snippet.name());
        assertEquals("changed", snippet.text());

        store.delete(id);
        assertTrue(store.list(null).isEmpty());
    }

    @Test
    void seedAddsExamplesOnlyOnce() {
        store.seedIfEmpty();
        store.seedIfEmpty();

        assertEquals(3, store.list(null).size());
        assertTrue(names(null).contains("commit"));
    }

    @Test
    void seedSkipsWhenTableHasSnippets() {
        store.add("mine", "t", null);
        store.seedIfEmpty();

        assertEquals(List.of("mine"), names(null));
    }
}
