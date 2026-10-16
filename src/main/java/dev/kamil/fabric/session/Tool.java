package dev.kamil.fabric.session;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A CLI that runs in a tab. A null or empty command means a plain login shell. */
public record Tool(String name, String command) {

    private static final List<String> BUILT_IN = List.of("claude", "codex", "gemini", "aider");
    public static final Tool SHELL = new Tool("shell", null);

    public boolean isShell() {
        return command == null || command.isBlank();
    }

    /** An ssh connection (quick-connect or a saved one): the tab offers to reconnect instead of becoming a shell. */
    public boolean isSsh() {
        return command != null && command.startsWith("ssh ");
    }

    /** An ssh connection to a config alias; the alias is quoted so it is never read as an ssh option or shell syntax. */
    public static Tool ssh(String alias) {
        return new Tool("ssh " + alias, "ssh -- " + quote(alias));
    }

    /**
     * An ssh connection to a host added in the app: {@code ssh [-p port] [-i identity] -- [user@]hostname}. Every value
     * is quoted and the destination follows {@code --}, so no value can become an option or shell syntax.
     */
    public static Tool ssh(String name, String hostName, String user, int port, String identity) {
        var command = new StringBuilder("ssh");
        if (port != 22) {
            command.append(" -p ").append(quote(String.valueOf(port)));
        }
        if (identity != null && !identity.isBlank()) {
            command.append(" -i ").append(quote(identity));
        }
        var destination = (user == null || user.isBlank() ? "" : user + "@") + hostName;
        command.append(" -- ").append(quote(destination));
        return new Tool("ssh " + name, command.toString());
    }

    private static String quote(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    static List<Tool> available() {
        return available(System.getenv("PATH"));
    }

    static List<Tool> available(String pathEnv) {
        var result = new ArrayList<Tool>();
        for (var name : BUILT_IN) {
            if (onPath(name, pathEnv)) {
                result.add(new Tool(name, name));
            }
        }
        result.add(SHELL);
        return result;
    }

    /** Claude if installed, else the first available tool. */
    public static Tool defaultTool() {
        return available().get(0);
    }

    static Tool custom(String commandLine) {
        return new Tool("custom", commandLine);
    }

    static Optional<Tool> byName(String name) {
        if ("shell".equals(name)) {
            return Optional.of(SHELL);
        }
        return BUILT_IN.contains(name) ? Optional.of(new Tool(name, name)) : Optional.empty();
    }

    static boolean onPath(String command) {
        return onPath(command, System.getenv("PATH"));
    }

    static boolean onPath(String command, String pathEnv) {
        if (pathEnv == null) {
            return false;
        }
        for (var entry : pathEnv.split(File.pathSeparator)) {
            if (!entry.isEmpty() && Files.isExecutable(Path.of(entry, command))
                    && Files.isRegularFile(Path.of(entry, command))) {
                return true;
            }
        }
        return false;
    }
}
