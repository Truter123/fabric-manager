package dev.kamil.fabric.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.session.Database;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskStoreTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    @TempDir
    Path tmp;

    private TaskStore store() {
        return new TaskStore(Database.at(tmp.resolve("db/fabric")).migrate());
    }

    @Test
    void failedWriteThrowsInsteadOfLosingTheTaskSilently() {
        var unmigrated = new TaskStore(Database.at(tmp.resolve("empty/fabric")));

        var e = assertThrows(IllegalStateException.class, () -> unmigrated.add("lost", null));

        assertTrue(e.getMessage().startsWith("Cannot save task"));
    }

    @Test
    void openListsDatedBeforeUndatedEarlierDateFirst() {
        var store = store();
        long none = store.add("none", null);
        long later = store.add("later", TODAY.plusDays(5));
        long soon = store.add("soon", TODAY.plusDays(1));

        var open = store.open();

        assertEquals(3, open.size());
        assertEquals(soon, open.get(0).id());
        assertEquals(later, open.get(1).id());
        assertEquals(none, open.get(2).id());
        assertNull(open.get(2).due());
    }

    @Test
    void doneMovesToDoneRecentAndUndoReturnsIt() {
        var store = store();
        long id = store.add("a", null);

        store.done(id);

        assertTrue(store.open().isEmpty());
        assertEquals(1, store.doneRecent(10).size());
        assertTrue(store.doneRecent(10).get(0).done());

        store.undo(id);

        assertEquals(1, store.open().size());
        assertTrue(store.doneRecent(10).isEmpty());
    }

    @Test
    void deleteRemovesTask() {
        var store = store();
        long id = store.add("a", null);

        store.delete(id);

        assertTrue(store.open().isEmpty());
    }

    @Test
    void setDueChangesAndClearsDate() {
        var store = store();
        long id = store.add("a", null);

        store.setDue(id, TODAY);
        assertEquals(TODAY, store.open().get(0).due());

        store.setDue(id, null);
        assertNull(store.open().get(0).due());
    }

    @Test
    void dueCountCountsOverdueAndTodayAndIgnoresDone() {
        var store = store();
        store.add("old", TODAY.minusDays(3));
        long doneOld = store.add("old done", TODAY.minusDays(1));
        store.add("today", TODAY);
        store.add("tomorrow", TODAY.plusDays(1));
        store.add("none", null);
        store.done(doneOld);

        var count = store.dueCount(TODAY);

        assertEquals(1, count.overdue());
        assertEquals(1, count.today());
    }
}
