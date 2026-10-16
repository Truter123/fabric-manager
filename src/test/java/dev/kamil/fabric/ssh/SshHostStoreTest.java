package dev.kamil.fabric.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.session.Database;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SshHostStoreTest {

    @TempDir
    Path tmp;

    SshHostStore store;

    @BeforeEach
    void setUp() {
        store = new SshHostStore(Database.at(tmp.resolve("db/fabric")).migrate());
    }

    @Test
    void addsAndListsWithDefaults() {
        long id = store.add("box", "10.0.0.1", " ", 22, "");
        var host = store.list().get(0);
        assertEquals(id, host.id());
        assertEquals("box", host.name());
        assertNull(host.user());
        assertNull(host.identity());
        assertEquals("10.0.0.1", host.detail());
    }

    @Test
    void updateChangesFields() {
        long id = store.add("box", "10.0.0.1", null, 22, null);
        store.update(id, "box2", "h", "me", 2200, "/k");
        var host = store.list().get(0);
        assertEquals(new AppHost(id, "box2", "h", "me", 2200, "/k"), host);
        assertEquals("me@h:2200", host.detail());
    }

    @Test
    void deleteRemoves() {
        long id = store.add("box", "h", null, 22, null);
        store.delete(id);
        assertTrue(store.list().isEmpty());
    }

    @Test
    void nameMustBeUniqueOnAddAndUpdate() {
        store.add("box", "h", null, 22, null);
        long other = store.add("other", "h", null, 22, null);
        assertThrows(IllegalStateException.class, () -> store.add("box", "h2", null, 22, null));
        assertThrows(IllegalStateException.class, () -> store.update(other, "box", "h", null, 22, null));
        store.update(other, "other", "h3", null, 22, null);
    }

    @Test
    void requiredFieldsAndPortRange() {
        assertThrows(IllegalStateException.class, () -> store.add(" ", "h", null, 22, null));
        assertThrows(IllegalStateException.class, () -> store.add("a", "", null, 22, null));
        assertThrows(IllegalStateException.class, () -> store.add("a", "h", null, 0, null));
        assertThrows(IllegalStateException.class, () -> store.add("a", "h", null, 70000, null));
    }

    @Test
    void hideAndUnhide() {
        store.hide("web");
        store.hide("web");
        assertEquals(Set.of("web"), store.hidden());
        store.unhide("web");
        assertTrue(store.hidden().isEmpty());
    }

    @Test
    void mergeListsAppHostsFirstAndHidesHiddenConfigHosts() {
        var config = List.of(new SshHost("a", "ha", null, null), new SshHost("b", "hb", null, null));
        var app = List.of(new AppHost(1, "zed", "hz", null, 22, null));
        var shown = SshEntry.merge(config, app, Set.of("a"), false);
        assertEquals(List.of("zed", "b"), shown.stream().map(SshEntry::alias).toList());
        var all = SshEntry.merge(config, app, Set.of("a"), true);
        assertEquals(List.of("zed", "a", "b"), all.stream().map(SshEntry::alias).toList());
        assertTrue(all.get(1).hidden());
        assertTrue(all.get(0).fromApp());
    }
}
