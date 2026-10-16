package dev.kamil.fabric.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {

    @TempDir
    Path tmp;

    private int tableCount(Database db) throws SQLException {
        try (var con = db.connect();
                var st = con.createStatement();
                var rs = st.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME IN ('PROJECT', 'SESSION')")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void migrateCreatesTablesAndIsRepeatable() throws SQLException {
        var db = new Database(tmp.resolve("db/fabric"));
        db.migrate();
        db.migrate();
        assertEquals(2, tableCount(db));
    }
}
