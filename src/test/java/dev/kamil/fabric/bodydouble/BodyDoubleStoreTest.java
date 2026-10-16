package dev.kamil.fabric.bodydouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.kamil.fabric.session.Database;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BodyDoubleStoreTest {

    @TempDir
    Path tmp;

    private BodyDoubleStore store() {
        return new BodyDoubleStore(Database.at(tmp.resolve("db/fabric")).migrate());
    }

    @Test
    void recentIsNewestFirstAndLimitedToTen() throws Exception {
        var store = store();
        for (int i = 1; i <= 12; i++) {
            store.upsert("url" + i, null);
            Thread.sleep(2);
        }

        var recent = store.recent(10);

        assertEquals(10, recent.size());
        assertEquals("url12", recent.get(0).url());
        assertEquals("url3", recent.get(9).url());
    }

    @Test
    void upsertByUrlKeepsOneRowPositionAndTitle() throws Exception {
        var store = store();
        store.upsert("a", "Title A");
        store.savePosition("a", 42_000);
        Thread.sleep(2);
        store.upsert("b", null);
        Thread.sleep(2);

        var again = store.upsert("a", null);

        assertEquals(2, store.recent(10).size());
        assertEquals("Title A", again.title());
        assertEquals(42_000, again.positionMs());
        assertEquals("a", store.recent(10).get(0).url());
    }

    @Test
    void savePositionAndTitleAreStored() {
        var store = store();
        store.upsert("a", null);

        store.savePosition("a", 1234);
        store.setTitle("a", "Late title");

        var video = store.find("a");
        assertEquals(1234, video.positionMs());
        assertEquals("Late title", video.title());
        assertEquals("a", new Video(1, "a", " ", 0, null).label());
        assertNull(store.find("missing"));
    }
}
