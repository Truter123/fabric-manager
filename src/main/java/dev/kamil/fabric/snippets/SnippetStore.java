package dev.kamil.fabric.snippets;

import dev.kamil.fabric.session.Database;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Snippets kept in the snippet table. */
public final class SnippetStore {

    private final Database db;

    public SnippetStore(Database db) {
        this.db = db;
    }

    /** Inserts a snippet (projectPath null = global) and returns its id, or -1 on failure. */
    public synchronized long add(String name, String text, String projectPath) {
        try (var con = db.connect();
                var ps = con.prepareStatement("INSERT INTO snippet (name, text, project_path) VALUES (?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, text);
            ps.setString(3, projectPath);
            ps.executeUpdate();
            try (var keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save snippet: " + e.getMessage(), e);
        }
    }

    public synchronized void update(long id, String name, String text, String projectPath) {
        try (var con = db.connect();
                var ps = con.prepareStatement("UPDATE snippet SET name = ?, text = ?, project_path = ? WHERE id = ?")) {
            ps.setString(1, name);
            ps.setString(2, text);
            ps.setString(3, projectPath);
            ps.setLong(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update snippet: " + e.getMessage(), e);
        }
    }

    public synchronized void delete(long id) {
        try (var con = db.connect();
                var ps = con.prepareStatement("DELETE FROM snippet WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot delete snippet: " + e.getMessage(), e);
        }
    }

    /** Global snippets plus those of the project (null = global only), recently used first, then by name. */
    public synchronized List<Snippet> list(String projectPath) {
        var snippets = new ArrayList<Snippet>();
        try (var con = db.connect();
                var ps = con.prepareStatement("SELECT id, name, text, project_path, use_count FROM snippet "
                        + "WHERE project_path IS NULL OR project_path = ? "
                        + "ORDER BY last_used_at DESC NULLS LAST, name, id")) {
            ps.setString(1, projectPath);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    snippets.add(new Snippet(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getInt(5)));
                }
            }
        } catch (SQLException e) {
            System.err.println("Cannot read snippets: " + e.getMessage());
        }
        return snippets;
    }

    /** Counts a use and stamps the time. */
    public synchronized void markUsed(long id) {
        markUsed(id, Instant.now());
    }

    synchronized void markUsed(long id, Instant at) {
        try (var con = db.connect();
                var ps = con.prepareStatement(
                        "UPDATE snippet SET use_count = use_count + 1, last_used_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, Timestamp.from(at));
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update snippet: " + e.getMessage(), e);
        }
    }

    /** Adds the three example snippets when the table is empty. */
    public synchronized void seedIfEmpty() {
        try (var con = db.connect();
                var st = con.createStatement();
                var rs = st.executeQuery("SELECT COUNT(*) FROM snippet")) {
            if (rs.next() && rs.getInt(1) > 0) {
                return;
            }
        } catch (SQLException e) {
            System.err.println("Cannot read snippets: " + e.getMessage());
            return;
        }
        add("review", "Review the current diff for bugs.", null);
        add("commit", "/commit", null);
        add("explain", "Explain how this part works: ", null);
    }
}
