package dev.kamil.fabric.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Recently opened project folders and the last tool, kept in the project table. */
public final class RecentProjects {

    private static final int MAX = 10;

    private final Database db;
    private final Path legacyFile;
    private final List<Path> projects = new ArrayList<>();
    private final Path home;
    private Tool lastTool;

    public RecentProjects(Database db, Path legacyFile) {
        this(db, legacyFile, Path.of(System.getProperty("user.home")));
    }

    public RecentProjects(Database db, Path legacyFile, Path home) {
        this.db = db;
        this.legacyFile = legacyFile;
        this.home = home;
    }

    /** Reads the newest rows, dropping folders that no longer exist. Imports the legacy file once. */
    public RecentProjects load() {
        projects.clear();
        lastTool = null;
        try (var con = db.connect()) {
            importLegacy(con);
            try (var st = con.createStatement();
                    var rs = st.executeQuery("SELECT path, last_tool FROM project ORDER BY last_opened DESC")) {
                boolean first = true;
                while (rs.next() && projects.size() < MAX) {
                    var dir = Path.of(rs.getString(1));
                    if (first) {
                        lastTool = ToolCodec.decode(rs.getString(2));
                        first = false;
                    }
                    if (Files.isDirectory(dir) && !projects.contains(dir)) {
                        projects.add(dir);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Cannot read recent projects: " + e.getMessage());
        }
        return this;
    }

    public void remember(Path dir, Tool tool, Account account) {
        projects.remove(dir);
        projects.add(0, dir);
        while (projects.size() > MAX) {
            projects.remove(projects.size() - 1);
        }
        lastTool = tool;
        try (var con = db.connect();
                var ps = con.prepareStatement("MERGE INTO project (path, last_opened, last_tool, account) KEY(path)"
                        + " VALUES (?, ?, ?, ?)")) {
            ps.setString(1, dir.toString());
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setString(3, ToolCodec.encode(tool));
            ps.setString(4, account.name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save recent project " + dir + ": " + e.getMessage(), e);
        }
    }

    /** The account stored for the project; a project without one gets {@link Account#defaultFor}. */
    public Account accountFor(Path dir) {
        var available = Account.available(home);
        try (var con = db.connect();
                var ps = con.prepareStatement("SELECT account FROM project WHERE path = ?")) {
            ps.setString(1, dir.toString());
            try (var rs = ps.executeQuery()) {
                if (rs.next() && rs.getString(1) != null) {
                    return Account.byName(rs.getString(1), home, available);
                }
            }
        } catch (SQLException e) {
            System.err.println("Cannot read account of " + dir + ": " + e.getMessage());
        }
        return Account.defaultFor(dir, home, available);
    }

    List<Path> projects() {
        return List.copyOf(projects);
    }

    public Optional<Tool> lastTool() {
        return Optional.ofNullable(lastTool);
    }

    /** One-time import of the old text file (line 1 = last tool, then paths newest first). */
    private void importLegacy(Connection con) throws SQLException {
        if (legacyFile == null || !Files.isRegularFile(legacyFile)) {
            return;
        }
        try (var st = con.createStatement(); var rs = st.executeQuery("SELECT COUNT(*) FROM project")) {
            rs.next();
            if (rs.getInt(1) > 0) {
                return;
            }
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(legacyFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Cannot read " + legacyFile + ": " + e.getMessage());
            return;
        }
        if (lines.isEmpty()) {
            return;
        }
        var tool = lines.get(0).trim();
        var now = Instant.now();
        int index = 0;
        try (var ps = con.prepareStatement(
                "MERGE INTO project (path, last_opened, last_tool) KEY(path) VALUES (?, ?, ?)")) {
            for (var line : lines.subList(1, lines.size())) {
                if (line.isBlank()) {
                    continue;
                }
                ps.setString(1, Path.of(line.trim()).toString());
                ps.setTimestamp(2, Timestamp.from(now.minusSeconds(index)));
                ps.setString(3, index == 0 && !tool.isEmpty() ? tool : null);
                ps.executeUpdate();
                index++;
            }
        }
        try {
            Files.move(legacyFile, legacyFile.resolveSibling("recent.bak"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Cannot rename " + legacyFile + ": " + e.getMessage());
        }
    }
}
