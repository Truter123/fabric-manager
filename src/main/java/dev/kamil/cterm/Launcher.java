package dev.kamil.cterm;

import javafx.application.Application;

/** Plain main class, so the app starts from the classpath without JavaFX module checks. */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(ClaudeTerminalApp.class, args);
    }
}
