package dev.kamil.fabric.quotes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.session.Database;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuoteStoreTest {

    @TempDir
    Path tmp;

    private QuoteStore store() {
        return new QuoteStore(Database.at(tmp.resolve("db/fabric")).migrate());
    }

    @Test
    void startsEmptyBecauseSeededQuotesAreRemoved() {
        assertTrue(store().all().isEmpty());
    }

    @Test
    void addUpdateAndDelete() {
        var store = store();
        int seeded = store.all().size();

        store.add("Be here now", " Someone ");
        var added = store.all().get(seeded);
        assertEquals("Be here now", added.text());
        assertEquals("Someone", added.author());

        store.update(added.id(), "Be there", "");
        var updated = store.all().get(seeded);
        assertEquals("Be there", updated.text());
        assertNull(updated.author());

        store.delete(added.id());
        assertEquals(seeded, store.all().size());
    }
}
