package dev.kamil.fabric.session;

import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Sessions started in tabs, kept in the session table so they can be resumed later. */
public final class SessionStore {

    private final Database db;
    private final Path home;
    private final Map<String, String> lastStatus = new HashMap<>();

    public SessionStore(Database db) {
        this(db, Path.of(System.getProperty("user.home")));
    }

    public SessionStore(Database db, Path home) {
        this.db = db;
        this.home = home;
    }

    /** Inserts the session, or restarts it (same id resumed): started now, not ended, no status. */
    public synchronized void started(String id, Path project, Tool tool, Account account, String title) {
        lastStatus.remove(id);
        var now = Timestamp.from(Instant.now());
        try (var con = db.connect()) {
            try (var ps = con.prepareStatement(
                    "INSERT INTO project (path, last_opened, last_tool) SELECT CAST(? AS VARCHAR), CAST(? AS TIMESTAMP),"
                            + " CAST(? AS VARCHAR) FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM project WHERE path = ?)")) {
                ps.setString(1, project.toString());
                ps.setTimestamp(2, now);
                ps.setString(3, ToolCodec.encode(tool));
                ps.setString(4, project.toString());
                ps.executeUpdate();
            }
            try (var ps = con.prepareStatement("MERGE INTO session (id, project_path, tool_name, tool_command, title,"
                    + " started_at, ended_at, last_status, account) KEY(id) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?)")) {
                ps.setString(1, id);
                ps.setString(2, project.toString());
                ps.setString(3, tool.name());
                ps.setString(4, tool.command());
                ps.setString(5, title);
                ps.setTimestamp(6, now);
                ps.setString(7, account.name());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save session " + id + ": " + e.getMessage(), e);
        }
    }

    public synchronized void renamed(String id, String title) {
        update(id, "title = ?", title);
    }

    public synchronized void ended(String id) {
        lastStatus.remove(id);
        update(id, "ended_at = ?", Timestamp.from(Instant.now()));
    }

    /** Writes the status only when it differs from the last one written for this session. */
    public synchronized void status(String id, String status) {
        if (status.equals(lastStatus.get(id))) {
            return;
        }
        lastStatus.put(id, status);
        update(id, "last_status = ?", status);
    }

    public synchronized void setPinned(String id, boolean pinned) {
        update(id, "pinned = ?", pinned);
    }

    public synchronized boolean isPinned(String id) {
        try (var con = db.connect();
                var ps = con.prepareStatement("SELECT pinned FROM session WHERE id = ?")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            System.err.println("Cannot read pin of session " + id + ": " + e.getMessage());
            return false;
        }
    }

    /** Deletes these sessions from the history; pinned ones are kept. */
    synchronized void delete(java.util.Collection<String> ids) {
        try (var con = db.connect();
                var ps = con.prepareStatement("DELETE FROM session WHERE id = ? AND pinned = FALSE")) {
            for (var id : ids) {
                ps.setString(1, id);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot delete sessions: " + e.getMessage(), e);
        }
    }

    /** Puts a deleted session back as it was; only unpinned sessions are ever deleted. */
    synchronized void restore(SavedSession session) {
        try (var con = db.connect();
                var ps = con.prepareStatement("MERGE INTO session (id, project_path, tool_name, tool_command, title,"
                        + " started_at, ended_at, last_status, account) KEY(id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, session.id());
            ps.setString(2, session.project().toString());
            ps.setString(3, session.tool().name());
            ps.setString(4, session.tool().command());
            ps.setString(5, session.title());
            ps.setTimestamp(6, Timestamp.from(session.startedAt()));
            ps.setTimestamp(7, session.endedAt() == null ? null : Timestamp.from(session.endedAt()));
            ps.setString(8, session.lastStatus());
            ps.setString(9, session.account().name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot restore session: " + e.getMessage(), e);
        }
    }

    List<SavedSession> recent(int limit) {
        return query("", null, limit);
    }

    List<SavedSession> recent(Path project, int limit) {
        return query("WHERE s.project_path = ? ", project.toString(), limit);
    }

    private void update(String id, String assignment, Object value) {
        try (var con = db.connect();
                var ps = con.prepareStatement("UPDATE session SET " + assignment + " WHERE id = ?")) {
            ps.setObject(1, value);
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update session " + id + ": " + e.getMessage(), e);
        }
    }

    private List<SavedSession> query(String where, String param, int limit) {
        var result = new ArrayList<SavedSession>();
        var accounts = Account.available(home);
        try (var con = db.connect();
                var ps = con.prepareStatement("SELECT s.id, s.project_path, s.tool_name, s.tool_command, s.title,"
                        + " s.started_at, s.ended_at, s.last_status, s.account FROM session s " + where
                        + "ORDER BY s.started_at DESC LIMIT ?")) {
            int i = 1;
            if (param != null) {
                ps.setString(i++, param);
            }
            ps.setInt(i, limit);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    var name = rs.getString(3);
                    var command = rs.getString(4);
                    var tool = ToolCodec.decode("custom".equals(name) ? "custom:" + command : name);
                    if (tool == null) {
                        tool = new Tool(name, command);
                    }
                    var ended = rs.getTimestamp(7);
                    result.add(new SavedSession(rs.getString(1), Path.of(rs.getString(2)), tool, rs.getString(5),
                            rs.getTimestamp(6).toInstant(), ended == null ? null : ended.toInstant(),
                            rs.getString(8), Account.byName(rs.getString(9), home, accounts)));
                }
            }
        } catch (SQLException e) {
            System.err.println("Cannot read sessions: " + e.getMessage());
        }
        return result;
    }
}
