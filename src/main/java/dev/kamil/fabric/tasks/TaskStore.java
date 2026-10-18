package dev.kamil.fabric.tasks;

import dev.kamil.fabric.session.Database;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Tasks kept in the task table. */
public final class TaskStore {

    private static final String COLUMNS = "id, text, due_date, done_at, created_at";

    private final Database db;

    public TaskStore(Database db) {
        this.db = db;
    }

    /** Inserts an open task with the current time and returns its id, or -1 on failure. */
    public synchronized long add(String text, LocalDate due) {
        try (var con = db.connect();
                var ps = con.prepareStatement("INSERT INTO task (text, due_date, created_at) VALUES (?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, text);
            ps.setDate(2, due == null ? null : Date.valueOf(due));
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.executeUpdate();
            try (var keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save task: " + e.getMessage(), e);
        }
    }

    /** Sets or clears (null) the due date. */
    public synchronized void setDue(long id, LocalDate due) {
        try (var con = db.connect();
                var ps = con.prepareStatement("UPDATE task SET due_date = ? WHERE id = ?")) {
            ps.setDate(1, due == null ? null : Date.valueOf(due));
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot change task date: " + e.getMessage(), e);
        }
    }

    /** Marks the task done now. */
    public synchronized void done(long id) {
        setDoneAt(id, Timestamp.from(Instant.now()));
    }

    /** Makes a done task open again. */
    public synchronized void undo(long id) {
        setDoneAt(id, null);
    }

    private void setDoneAt(long id, Timestamp doneAt) {
        try (var con = db.connect();
                var ps = con.prepareStatement("UPDATE task SET done_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, doneAt);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update task: " + e.getMessage(), e);
        }
    }

    public synchronized void delete(long id) {
        try (var con = db.connect();
                var ps = con.prepareStatement("DELETE FROM task WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot delete task: " + e.getMessage(), e);
        }
    }

    /** Puts a deleted task back with its dates under a new id and returns it, or -1 on failure. */
    public synchronized long restore(Task task) {
        try (var con = db.connect();
                var ps = con.prepareStatement(
                        "INSERT INTO task (text, due_date, done_at, created_at) VALUES (?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, task.text());
            ps.setDate(2, task.due() == null ? null : Date.valueOf(task.due()));
            ps.setTimestamp(3, task.doneAt() == null ? null : Timestamp.from(task.doneAt()));
            ps.setTimestamp(4, Timestamp.from(task.createdAt()));
            ps.executeUpdate();
            try (var keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot restore task: " + e.getMessage(), e);
        }
    }

    /** Open tasks: dated first (earliest first), undated last, then oldest first. */
    public synchronized List<Task> open() {
        return query("SELECT " + COLUMNS + " FROM task WHERE done_at IS NULL "
                + "ORDER BY due_date ASC NULLS LAST, created_at, id", 0);
    }

    /** The most recently completed tasks, newest first. */
    public synchronized List<Task> doneRecent(int limit) {
        return query("SELECT " + COLUMNS + " FROM task WHERE done_at IS NOT NULL "
                + "ORDER BY done_at DESC, id DESC LIMIT ?", limit);
    }

    /** Counts open tasks due before today and due today. */
    public synchronized DueCount dueCount(LocalDate today) {
        try (var con = db.connect();
                var ps = con.prepareStatement("SELECT "
                        + "COALESCE(SUM(CASE WHEN due_date < ? THEN 1 ELSE 0 END), 0), "
                        + "COALESCE(SUM(CASE WHEN due_date = ? THEN 1 ELSE 0 END), 0) "
                        + "FROM task WHERE done_at IS NULL AND due_date IS NOT NULL")) {
            ps.setDate(1, Date.valueOf(today));
            ps.setDate(2, Date.valueOf(today));
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new DueCount(rs.getInt(1), rs.getInt(2));
                }
            }
        } catch (SQLException e) {
            System.err.println("Cannot count tasks: " + e.getMessage());
        }
        return new DueCount(0, 0);
    }

    private List<Task> query(String sql, int limit) {
        var tasks = new ArrayList<Task>();
        try (var con = db.connect();
                var ps = con.prepareStatement(sql)) {
            if (sql.endsWith("LIMIT ?")) {
                ps.setInt(1, limit);
            }
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    tasks.add(read(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Cannot read tasks: " + e.getMessage());
        }
        return tasks;
    }

    private static Task read(ResultSet rs) throws SQLException {
        var due = rs.getDate(3);
        var doneAt = rs.getTimestamp(4);
        return new Task(rs.getLong(1), rs.getString(2), due == null ? null : due.toLocalDate(),
                doneAt == null ? null : doneAt.toInstant(), rs.getTimestamp(5).toInstant());
    }
}
