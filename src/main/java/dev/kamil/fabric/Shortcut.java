package dev.kamil.fabric;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

/**
 * Every accelerator of the main window, in the order the F1 sheet lists them. FabricManagerApp binds each one by
 * constant, so a key that is bound is always on the sheet; a test checks the README table against {@link #keys()}.
 * Ctrl+Shift+D is also the terminal's own split key: a focused terminal consumes it before the window sees it.
 */
public enum Shortcut {
    NEW(KeyCode.N, "new session"),
    SHELL(KeyCode.T, "shell in this folder"),
    SETTINGS(KeyCode.COMMA, "Claude settings"),
    NOTES(KeyCode.J, "notes"),
    TASKS(KeyCode.K, "tasks"),
    SNIPPETS(KeyCode.S, "snippets"),
    HISTORY(KeyCode.H, "git history"),
    PROCESSES(KeyCode.P, "Docker, Java and Node"),
    VIDEO(KeyCode.V, "body double video"),
    QUOTES(KeyCode.Q, "your quotes"),
    ENGLISH(KeyCode.E, "English sentences"),
    AGENTS(KeyCode.A, "agents panel"),
    OVERVIEW(KeyCode.O, "overview of all sessions"),
    SSH(KeyCode.G, "connect to a server"),
    WORKTREE(KeyCode.B, "new session in a git worktree"),
    CHANGES(KeyCode.C, "git changes of this session"),
    WATER(KeyCode.I, "water reminder"),
    SERVERS(KeyCode.M, "fleet servers and app status"),
    SEARCH(KeyCode.F, "search Claude transcripts"),
    NEXT_TAB(new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN), "Ctrl+Tab", "next tab"),
    RENAME(KeyCode.R, "rename session"),
    DUPLICATE(KeyCode.D, "split terminal (in a terminal) / duplicate session"),
    CLOSE(KeyCode.W, "close tab"),
    PREVIOUS_TAB(KeyCode.TAB, "previous tab"),
    KEYS(new KeyCodeCombination(KeyCode.F1), "F1", "keys");

    private final KeyCombination combo;
    private final String keys;
    private final String what;

    /** Ctrl+Shift plus one key. */
    Shortcut(KeyCode code, String what) {
        this(new KeyCodeCombination(code, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN),
                "Ctrl+Shift+" + (code == KeyCode.COMMA ? "," : code.getName()), what);
    }

    Shortcut(KeyCombination combo, String keys, String what) {
        this.combo = combo;
        this.keys = keys;
        this.what = what;
    }

    public KeyCombination combo() {
        return combo;
    }

    /** The key as shown to the user: "Ctrl+Shift+N". */
    public String keys() {
        return keys;
    }

    /** What the key does, in a few words. */
    public String what() {
        return what;
    }

    /** The sidebar button tooltip tail: "Ctrl+Shift+N  new session". */
    public String tooltip() {
        return keys + "  " + what;
    }
}
