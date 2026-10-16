package dev.kamil.fabric.quotes;

import dev.kamil.fabric.session.Database;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Quotes kept in the quote table. */
public final class QuoteStore {

    private final Database db;

    public QuoteStore(Database db) {
        this.db = db;
    }

    /** All quotes, ordered by id. */
    public synchronized List<Quote> all() {
        var quotes = new ArrayList<Quote>();
        try (var con = db.connect();
                var ps = con.prepareStatement("SELECT id, text, author FROM quote ORDER BY id");
                var rs = ps.executeQuery()) {
            while (rs.next()) {
                quotes.add(new Quote(rs.getLong(1), rs.getString(2), rs.getString(3)));
            }
        } catch (SQLException e) {
            System.err.println("Cannot read quotes: " + e.getMessage());
        }
        return quotes;
    }

    public synchronized void add(String text, String author) {
        try (var con = db.connect();
                var ps = con.prepareStatement("INSERT INTO quote (text, author, created_at) VALUES (?, ?, ?)")) {
            ps.setString(1, text);
            ps.setString(2, blankToNull(author));
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot add quote: " + e.getMessage(), e);
        }
    }

    public synchronized void update(long id, String text, String author) {
        try (var con = db.connect();
                var ps = con.prepareStatement("UPDATE quote SET text = ?, author = ? WHERE id = ?")) {
            ps.setString(1, text);
            ps.setString(2, blankToNull(author));
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update quote: " + e.getMessage(), e);
        }
    }

    public synchronized void delete(long id) {
        try (var con = db.connect();
                var ps = con.prepareStatement("DELETE FROM quote WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot delete quote: " + e.getMessage(), e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
