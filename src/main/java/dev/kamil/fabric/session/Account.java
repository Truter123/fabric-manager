package dev.kamil.fabric.session;

import dev.kamil.fabric.settings.AppSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A Claude Code login: its config directory ({@code CLAUDE_CONFIG_DIR}). Personal is {@code ~/.claude}. */
public record Account(String name, Path configDir) {

    public static final String PERSONAL = "personal";

    /** Projects under this folder (relative to home) default to the {@link #WORK} account. */
    public static final String WORK_FOLDER = "Documents/Amu";

    /** Setting that overrides {@link #WORK_FOLDER}; relative to home unless absolute. */
    public static final String WORK_FOLDER_KEY = "accounts.workFolder";

    static final String WORK = "work";

    public static Account personal(Path home) {
        return new Account(PERSONAL, home.resolve(".claude"));
    }

    /** Personal plus one account per {@code ~/.claude-<x>} directory, sorted by name. */
    public static List<Account> available(Path home) {
        var result = new ArrayList<Account>();
        result.add(personal(home));
        var others = new ArrayList<Account>();
        try (var dirs = Files.newDirectoryStream(home, ".claude-*")) {
            for (var dir : dirs) {
                if (Files.isDirectory(dir)) {
                    others.add(new Account(dir.getFileName().toString().substring(".claude-".length()), dir));
                }
            }
        } catch (IOException e) {
            System.err.println("Cannot list accounts in " + home + ": " + e.getMessage());
        }
        others.sort(Comparator.comparing(Account::name));
        result.addAll(others);
        return result;
    }

    public boolean isPersonal() {
        return PERSONAL.equals(name);
    }

    /** The account a new project starts with: work under the work folder when it exists, else personal. */
    public static Account defaultFor(Path project, Path home, List<Account> available) {
        return defaultFor(project, home, available, AppSettings.shared().getString(WORK_FOLDER_KEY, WORK_FOLDER));
    }

    /** Setting key of the display label of the account called {@code name}. */
    public static String labelKey(String name) {
        return "account." + name + ".label";
    }

    public static Account defaultFor(Path project, Path home, List<Account> available, String workFolder) {
        var folder = workFolder == null || workFolder.isBlank() ? WORK_FOLDER : workFolder;
        if (project != null && project.startsWith(home.resolve(folder))) {
            for (var account : available) {
                if (WORK.equals(account.name())) {
                    return account;
                }
            }
        }
        return personal(home);
    }

    /** Claude's per-account state file with the MCP servers: {@code ~/.claude.json} for personal. */
    public Path mcpFile(Path home) {
        return isPersonal() ? home.resolve(".claude.json") : configDir.resolve(".claude.json");
    }

    /** The account with that name; null or unknown falls back to personal. */
    public static Account byName(String name, Path home, List<Account> available) {
        if (name != null) {
            for (var account : available) {
                if (account.name().equals(name)) {
                    return account;
                }
            }
        }
        return personal(home);
    }
}
