package dev.kamil.fabric.ssh;

import dev.kamil.fabric.session.Database;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Hosts added in the app (ssh_host) and config aliases the user hid (ssh_hidden). ~/.ssh/config is never written. */
public final class SshHostStore {

    private final Database db;

    public SshHostStore(Database db) {
        this.db = db;
    }

    /** Inserts a host and returns its id. A blank user or identity is stored as null. */
    public synchronized long add(String name, String hostName, String user, int port, String identity) {
        check(name, hostName, port);
        if (nameTaken(name, -1)) {
            throw new IllegalStateException("A host named '" + name.strip() + "' already exists");
        }
        try (var con = db.connect();
                var ps = con.prepareStatement("INSERT INTO ssh_host (name, host_name, user_name, port, identity_file) "
                        + "VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, name, hostName, user, port, identity);
            ps.executeUpdate();
            try (var keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save host: " + e.getMessage(), e);
        }
    }

    public synchronized void update(long id, String name, String hostName, String user, int port, String identity) {
        check(name, hostName, port);
        if (nameTaken(name, id)) {
            throw new IllegalStateException("A host named '" + name.strip() + "' already exists");
        }
        try (var con = db.connect();
                var ps = con.prepareStatement("UPDATE ssh_host SET name = ?, host_name = ?, user_name = ?, port = ?, "
                        + "identity_file = ? WHERE id = ?")) {
            bind(ps, name, hostName, user, port, identity);
            ps.setLong(6, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update host: " + e.getMessage(), e);
        }
    }

    public synchronized void delete(long id) {
        try (var con = db.connect();
                var ps = con.prepareStatement("DELETE FROM ssh_host WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot delete host: " + e.getMessage(), e);
        }
    }

    public synchronized List<AppHost> list() {
        var hosts = new ArrayList<AppHost>();
        try (var con = db.connect();
                var st = con.createStatement();
                var rs = st.executeQuery("SELECT id, name, host_name, user_name, port, identity_file FROM ssh_host "
                        + "ORDER BY name, id")) {
            while (rs.next()) {
                hosts.add(new AppHost(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                        rs.getString(6)));
            }
        } catch (SQLException e) {
            System.err.println("Cannot read hosts: " + e.getMessage());
        }
        return hosts;
    }

    /** Hides a config alias from the picker; the config file is untouched. Hiding twice is fine. */
    public synchronized void hide(String alias) {
        if (hidden().contains(alias)) {
            return;
        }
        try (var con = db.connect();
                var ps = con.prepareStatement("INSERT INTO ssh_hidden (alias) VALUES (?)")) {
            ps.setString(1, alias);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot hide host: " + e.getMessage(), e);
        }
    }

    public synchronized void unhide(String alias) {
        try (var con = db.connect();
                var ps = con.prepareStatement("DELETE FROM ssh_hidden WHERE alias = ?")) {
            ps.setString(1, alias);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot unhide host: " + e.getMessage(), e);
        }
    }

    public synchronized Set<String> hidden() {
        var aliases = new LinkedHashSet<String>();
        try (var con = db.connect();
                var st = con.createStatement();
                var rs = st.executeQuery("SELECT alias FROM ssh_hidden ORDER BY alias")) {
            while (rs.next()) {
                aliases.add(rs.getString(1));
            }
        } catch (SQLException e) {
            System.err.println("Cannot read hidden hosts: " + e.getMessage());
        }
        return aliases;
    }

    private static void check(String name, String hostName, int port) {
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("The name is required");
        }
        if (hostName == null || hostName.isBlank()) {
            throw new IllegalStateException("The host name is required");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("The port must be 1-65535");
        }
    }

    private boolean nameTaken(String name, long exceptId) {
        try (var con = db.connect();
                var ps = con.prepareStatement("SELECT COUNT(*) FROM ssh_host WHERE name = ? AND id <> ?")) {
            ps.setString(1, name.strip());
            ps.setLong(2, exceptId);
            try (var rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read hosts: " + e.getMessage(), e);
        }
    }

    private static void bind(java.sql.PreparedStatement ps, String name, String hostName, String user, int port,
            String identity) throws SQLException {
        ps.setString(1, name.strip());
        ps.setString(2, hostName.strip());
        ps.setString(3, user == null || user.isBlank() ? null : user.strip());
        ps.setInt(4, port);
        ps.setString(5, identity == null || identity.isBlank() ? null : identity.strip());
    }
}
