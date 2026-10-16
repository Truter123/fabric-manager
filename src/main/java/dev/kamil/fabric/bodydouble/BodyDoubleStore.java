package dev.kamil.fabric.bodydouble;

import dev.kamil.fabric.session.Database;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Videos kept in the video table, with the position to resume from. */
public final class BodyDoubleStore {

    private static final String COLUMNS = "id, url, title, position_ms, last_played_at";

    private final Database db;

    public BodyDoubleStore(Database db) {
        this.db = db;
    }

    /**
     * Inserts the video or, when the URL is known, marks it played now. A null title keeps the stored one.
     * Returns the stored video, or null on failure.
     */
    public synchronized Video upsert(String url, String title) {
        try (var con = db.connect()) {
            var now = Timestamp.from(Instant.now());
            try (var ps = con.prepareStatement(
                    "UPDATE video SET last_played_at = ?, title = COALESCE(?, title) WHERE url = ?")) {
                ps.setTimestamp(1, now);
                ps.setString(2, title);
                ps.setString(3, url);
                if (ps.executeUpdate() == 0) {
                    try (var insert = con.prepareStatement(
                            "INSERT INTO video (url, title, position_ms, last_played_at) VALUES (?, ?, 0, ?)")) {
                        insert.setString(1, url);
                        insert.setString(2, title);
                        insert.setTimestamp(3, now);
                        insert.executeUpdate();
                    }
                }
            }
            return find(con, url);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save video: " + e.getMessage(), e);
        }
    }

    /** Stores the playback position of the video. */
    public synchronized void savePosition(String url, long positionMs) {
        try (var con = db.connect();
                var ps = con.prepareStatement("UPDATE video SET position_ms = ? WHERE url = ?")) {
            ps.setLong(1, Math.max(0, positionMs));
            ps.setString(2, url);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save video position: " + e.getMessage(), e);
        }
    }

    /** Sets the title of a known video. */
    public synchronized void setTitle(String url, String title) {
        try (var con = db.connect();
                var ps = con.prepareStatement("UPDATE video SET title = ? WHERE url = ?")) {
            ps.setString(1, title);
            ps.setString(2, url);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save video title: " + e.getMessage(), e);
        }
    }

    /** The video with this URL, or null. */
    public synchronized Video find(String url) {
        try (var con = db.connect()) {
            return find(con, url);
        } catch (SQLException e) {
            System.err.println("Cannot read video: " + e.getMessage());
            return null;
        }
    }

    /** The most recently played videos, newest first. */
    public synchronized List<Video> recent(int limit) {
        var videos = new ArrayList<Video>();
        try (var con = db.connect();
                var ps = con.prepareStatement("SELECT " + COLUMNS
                        + " FROM video ORDER BY last_played_at DESC, id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    videos.add(read(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Cannot read videos: " + e.getMessage());
        }
        return videos;
    }

    private static Video find(java.sql.Connection con, String url) throws SQLException {
        try (var ps = con.prepareStatement("SELECT " + COLUMNS + " FROM video WHERE url = ?")) {
            ps.setString(1, url);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    private static Video read(ResultSet rs) throws SQLException {
        var played = rs.getTimestamp(5);
        return new Video(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                played == null ? null : played.toInstant());
    }
}
