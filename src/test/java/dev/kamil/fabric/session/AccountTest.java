package dev.kamil.fabric.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountTest {

    @TempDir
    Path home;

    @Test
    void personalIsDotClaude() {
        var personal = Account.personal(home);
        assertEquals("personal", personal.name());
        assertEquals(home.resolve(".claude"), personal.configDir());
        assertTrue(personal.isPersonal());
    }

    @Test
    void discoversClaudeDirectoriesSorted() throws IOException {
        Files.createDirectories(home.resolve(".claude-work"));
        Files.createDirectories(home.resolve(".claude-alpha"));
        Files.writeString(home.resolve(".claude-file"), "x");
        var names = Account.available(home).stream().map(Account::name).toList();
        assertEquals(List.of("personal", "alpha", "work"), names);
    }

    @Test
    void onlyPersonalWhenNoOtherDirectories() {
        assertEquals(List.of(Account.personal(home)), Account.available(home));
    }

    @Test
    void workFolderDefaultsToWorkWhenItExists() throws IOException {
        var amu = home.resolve("Documents/Amu/proj");
        assertEquals("personal", Account.defaultFor(amu, home, Account.available(home)).name());
        Files.createDirectories(home.resolve(".claude-work"));
        var available = Account.available(home);
        assertEquals("work", Account.defaultFor(amu, home, available).name());
        assertEquals("personal", Account.defaultFor(home.resolve("Documents/other"), home, available).name());
    }

    @Test
    void mcpFileFollowsAccount() {
        var work = new Account("work", home.resolve(".claude-work"));
        assertEquals(home.resolve(".claude.json"), Account.personal(home).mcpFile(home));
        assertEquals(home.resolve(".claude-work/.claude.json"), work.mcpFile(home));
    }

    @Test
    void byNameFallsBackToPersonal() throws IOException {
        Files.createDirectories(home.resolve(".claude-work"));
        var available = Account.available(home);
        assertEquals("work", Account.byName("work", home, available).name());
        assertFalse(Account.byName("nope", home, available).name().equals("work"));
        assertTrue(Account.byName(null, home, available).isPersonal());
    }

    @Test
    void customWorkFolderDecidesTheDefaultAccount() throws IOException {
        Files.createDirectories(home.resolve(".claude-work"));
        var available = Account.available(home);
        var custom = home.resolve("Projects/Team/app");
        assertEquals("personal", Account.defaultFor(custom, home, available, "Documents/Amu").name());
        assertEquals("work", Account.defaultFor(custom, home, available, "Projects/Team").name());
        assertEquals("work", Account.defaultFor(custom, home, available, home.resolve("Projects").toString()).name());
        assertEquals("personal", Account.defaultFor(home.resolve("Documents/Amu/x"), home, available, "Projects/Team").name());
    }
}
