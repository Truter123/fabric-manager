package dev.kamil.fabric.settings;

import dev.kamil.fabric.session.Database;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Application settings kept in the app_setting table, read through typed getters with a default. Without a
 * database ({@link #inMemory()}) they live in memory only. Listeners get the changed key.
 */
public final class AppSettings {

    private static volatile AppSettings shared = new AppSettings(null);

    private final Database db;
    private final Map<String, String> values = new ConcurrentHashMap<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    /** Loads all stored settings; a null database keeps them in memory only. */
    public AppSettings(Database db) {
        this.db = db;
        if (db != null) {
            load();
        }
    }

    public static AppSettings inMemory() {
        return new AppSettings(null);
    }

    /** The settings the running app uses; defaults only until {@link #use} is called. */
    public static AppSettings shared() {
        return shared;
    }

    public static AppSettings use(AppSettings settings) {
        shared = settings;
        return settings;
    }

    public String getString(String key, String def) {
        var value = values.get(key);
        return value == null ? def : value;
    }

    public boolean getBoolean(String key, boolean def) {
        var value = values.get(key);
        return value == null ? def : Boolean.parseBoolean(value);
    }

    public int getInt(String key, int def) {
        var value = values.get(key);
        if (value == null) {
            return def;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public void setString(String key, String value) {
        if (value == null) {
            remove(key);
            return;
        }
        var previous = values.put(key, value);
        if (value.equals(previous)) {
            return;
        }
        try {
            store(key, value);
        } catch (RuntimeException e) {
            if (previous == null) {
                values.remove(key);
            } else {
                values.put(key, previous);
            }
            throw e;
        }
        fire(key);
    }

    public void setBoolean(String key, boolean value) {
        setString(key, Boolean.toString(value));
    }

    public void setInt(String key, int value) {
        setString(key, Integer.toString(value));
    }

    /** Back to the default. */
    public void remove(String key) {
        var previous = values.remove(key);
        if (previous != null) {
            try {
                store(key, null);
            } catch (RuntimeException e) {
                values.put(key, previous);
                throw e;
            }
            fire(key);
        }
    }

    public void removeAll(Collection<String> keys) {
        keys.forEach(this::remove);
    }

    public void addListener(Consumer<String> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<String> listener) {
        listeners.remove(listener);
    }

    private void fire(String key) {
        for (var listener : listeners) {
            listener.accept(key);
        }
    }

    private void load() {
        try (var con = db.connect();
                var st = con.createStatement();
                var rs = st.executeQuery("SELECT \"KEY\", \"VALUE\" FROM app_setting")) {
            while (rs.next()) {
                values.put(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException e) {
            System.err.println("Cannot read settings: " + e.getMessage());
        }
    }

    /** Writes the value (null deletes the row). */
    private synchronized void store(String key, String value) {
        if (db == null) {
            return;
        }
        try (var con = db.connect()) {
            try (var ps = con.prepareStatement("DELETE FROM app_setting WHERE \"KEY\" = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
            if (value != null) {
                try (var ps = con.prepareStatement("INSERT INTO app_setting (\"KEY\", \"VALUE\") VALUES (?, ?)")) {
                    ps.setString(1, key);
                    ps.setString(2, value);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save setting " + key + ": " + e.getMessage(), e);
        }
    }
}
