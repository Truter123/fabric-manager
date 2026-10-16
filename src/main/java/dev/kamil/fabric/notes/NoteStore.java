package dev.kamil.fabric.notes;

import dev.kamil.fabric.session.Database;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Notes kept in the note table. Lists put pinned notes first, then the most recently updated. */
public final class NoteStore {

    private static final String COLUMNS = "id, title, text, corrected, tags, pinned, inbox, project_path,"
            + " created_at, updated_at";
    private static final String ORDER = " ORDER BY pinned DESC, updated_at DESC, id DESC";
    private static final Pattern TAG = Pattern.compile("(?<![\\p{L}\\p{N}#])#([\\p{L}\\p{N}_-]+)");

    private final Database db;

    public NoteStore(Database db) {
        this.db = db;
    }

    /** The lowercase #tags of the text, space separated, in order of appearance; null when none. */
    public static String parseTags(String text) {
        var tags = new LinkedHashSet<String>();
        var m = TAG.matcher(text);
        while (m.find()) {
            tags.add(m.group(1).toLowerCase());
        }
        return tags.isEmpty() ? null : String.join(" ", tags);
    }

    /** Inserts a note with the current time and returns its id, or -1 on failure. */
    public long add(String text) {
        return create(null, text, false);
    }

    /** Quick capture: a note that waits in the inbox. */
    public long capture(String text) {
        return create(null, text, true);
    }

    /** Inserts a note with the current time and returns its id, or -1 on failure. */
    public synchronized long create(String title, String text, boolean inbox) {
        try (var con = db.connect();
                var ps = con.prepareStatement("INSERT INTO note (title, text, tags, inbox, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            var now = Timestamp.from(Instant.now());
            ps.setString(1, blankToNull(title));
            ps.setString(2, text);
            ps.setString(3, parseTags(text));
            ps.setBoolean(4, inbox);
            ps.setTimestamp(5, now);
            ps.setTimestamp(6, now);
            ps.executeUpdate();
            try (var keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save note: " + e.getMessage(), e);
        }
    }

    /** Re-inserts a deleted note under a new id, keeping its fields; returns the id or -1. */
    public synchronized long restore(Note note) {
        long id = create(note.title(), note.text(), note.inbox());
        if (id < 0) {
            return -1;
        }
        run("UPDATE note SET corrected = ?, pinned = ?, project_path = ?, created_at = ?, updated_at = ?"
                + " WHERE id = ?", ps -> {
                    ps.setString(1, note.corrected());
                    ps.setBoolean(2, note.pinned());
                    ps.setString(3, note.projectPath());
                    ps.setTimestamp(4, Timestamp.from(note.createdAt()));
                    ps.setTimestamp(5, Timestamp.from(note.updatedAt()));
                    ps.setLong(6, id);
                });
        return id;
    }

    /** Project folders that notes can be linked to, most recently opened first. */
    public synchronized List<String> projectPaths() {
        var paths = new ArrayList<String>();
        try (var con = db.connect();
                var st = con.createStatement();
                var rs = st.executeQuery("SELECT path FROM project ORDER BY last_opened DESC")) {
            while (rs.next()) {
                paths.add(rs.getString(1));
            }
        } catch (SQLException e) {
            System.err.println("Cannot read projects: " + e.getMessage());
        }
        return paths;
    }

    /** Replaces title and body, re-parses the tags and leaves the inbox. */
    public synchronized void update(long id, String title, String text) {
        run("UPDATE note SET title = ?, text = ?, tags = ?, inbox = FALSE, updated_at = ? WHERE id = ?",
                ps -> {
                    ps.setString(1, blankToNull(title));
                    ps.setString(2, text);
                    ps.setString(3, parseTags(text));
                    ps.setTimestamp(4, Timestamp.from(Instant.now()));
                    ps.setLong(5, id);
                });
    }

    public synchronized void delete(long id) {
        run("DELETE FROM note WHERE id = ?", ps -> ps.setLong(1, id));
    }

    public synchronized void setPinned(long id, boolean pinned) {
        run("UPDATE note SET pinned = ? WHERE id = ?", ps -> {
            ps.setBoolean(1, pinned);
            ps.setLong(2, id);
        });
    }

    /** Links the note to a project folder; null unlinks. */
    public synchronized void setProject(long id, String projectPath) {
        run("UPDATE note SET project_path = ? WHERE id = ?", ps -> {
            ps.setString(1, projectPath);
            ps.setLong(2, id);
        });
    }

    /** Stores the corrected text of a note. */
    public synchronized void corrected(long id, String corrected) {
        run("UPDATE note SET corrected = ? WHERE id = ?", ps -> {
            ps.setString(1, corrected);
            ps.setLong(2, id);
        });
    }

    /** One note by id, or null. */
    public synchronized Note find(long id) {
        var found = query("SELECT " + COLUMNS + " FROM note WHERE id = ?", ps -> ps.setLong(1, id));
        return found.isEmpty() ? null : found.get(0);
    }

    /** Pinned first, then the most recently updated. */
    public synchronized List<Note> recent(int limit) {
        return query("SELECT " + COLUMNS + " FROM note" + ORDER + " LIMIT ?", ps -> ps.setInt(1, limit));
    }

    public synchronized List<Note> inbox() {
        return query("SELECT " + COLUMNS + " FROM note WHERE inbox = TRUE" + ORDER, ps -> { });
    }

    public synchronized List<Note> pinned() {
        return query("SELECT " + COLUMNS + " FROM note WHERE pinned = TRUE" + ORDER, ps -> { });
    }

    /** Case-insensitive match over title, body and tags. */
    public synchronized List<Note> search(String query) {
        var like = "%" + query.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return query("SELECT " + COLUMNS + " FROM note WHERE LOWER(COALESCE(title, '')) LIKE ? ESCAPE '\\'"
                + " OR LOWER(text) LIKE ? ESCAPE '\\' OR LOWER(COALESCE(tags, '')) LIKE ? ESCAPE '\\'" + ORDER,
                ps -> {
                    ps.setString(1, like);
                    ps.setString(2, like);
                    ps.setString(3, like);
                });
    }

    /** Notes carrying exactly this tag (without the #). */
    public synchronized List<Note> byTag(String tag) {
        return query("SELECT " + COLUMNS + " FROM note WHERE ' ' || COALESCE(tags, '') || ' ' LIKE ?" + ORDER,
                ps -> ps.setString(1, "% " + tag.toLowerCase() + " %"));
    }

    public synchronized List<Note> byProject(String projectPath) {
        return query("SELECT " + COLUMNS + " FROM note WHERE project_path = ?" + ORDER,
                ps -> ps.setString(1, projectPath));
    }

    /** All distinct tags in use, sorted. */
    public synchronized List<String> allTags() {
        var tags = new java.util.TreeSet<String>();
        try (var con = db.connect();
                var st = con.createStatement();
                var rs = st.executeQuery("SELECT tags FROM note WHERE tags IS NOT NULL")) {
            while (rs.next()) {
                tags.addAll(List.of(rs.getString(1).split(" ")));
            }
        } catch (SQLException e) {
            System.err.println("Cannot read tags: " + e.getMessage());
        }
        return new ArrayList<>(tags);
    }

    private interface Binder {
        void bind(java.sql.PreparedStatement ps) throws SQLException;
    }

    private void run(String sql, Binder binder) {
        try (var con = db.connect(); var ps = con.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot change note: " + e.getMessage(), e);
        }
    }

    private List<Note> query(String sql, Binder binder) {
        var notes = new ArrayList<Note>();
        try (var con = db.connect(); var ps = con.prepareStatement(sql)) {
            binder.bind(ps);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    notes.add(read(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Cannot read notes: " + e.getMessage());
        }
        return notes;
    }

    private static Note read(ResultSet rs) throws SQLException {
        return new Note(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getBoolean(6), rs.getBoolean(7), rs.getString(8), rs.getTimestamp(9).toInstant(),
                rs.getTimestamp(10).toInstant());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
