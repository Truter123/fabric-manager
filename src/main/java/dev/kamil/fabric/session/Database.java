package dev.kamil.fabric.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import liquibase.Scope;
import liquibase.command.CommandScope;
import liquibase.resource.ClassLoaderResourceAccessor;

/** Embedded H2 database in one file; the schema is managed by Liquibase. */
public final class Database {

    private static final String CHANGELOG = "db/changelog.yaml";

    private final String url;

    /** @param file the H2 base path, without the .mv.db suffix */
    Database(Path file) {
        this.url = "jdbc:h2:file:" + file.toAbsolutePath() + ";AUTO_SERVER=TRUE";
    }

    /** A database at the given H2 base path. */
    public static Database at(Path file) {
        return new Database(file);
    }

    /** The per-user database under ~/.local/share/fabric-manager. */
    public static Database standard() {
        var dir = Path.of(System.getProperty("user.home")).resolve(".local/share/fabric-manager");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            System.err.println("Cannot create " + dir + ": " + e.getMessage());
        }
        return new Database(dir.resolve("fabric"));
    }

    /** Applies pending changesets; a no-op when the schema is current. */
    public Database migrate() {
        System.setProperty("liquibase.analytics.enabled", "false");
        System.setProperty("liquibase.showBanner", "false");
        try {
            Scope.child(Scope.Attr.resourceAccessor.name(), new ClassLoaderResourceAccessor(), () -> {
                new CommandScope("update")
                        .addArgumentValue("changelogFile", CHANGELOG)
                        .addArgumentValue("url", url)
                        .addArgumentValue("username", "sa")
                        .addArgumentValue("password", "")
                        .execute();
            });
        } catch (Exception e) {
            throw new IllegalStateException("Database migration failed: " + e.getMessage(), e);
        }
        return this;
    }

    /** Opens a connection; callers close it. */
    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
    }
}
