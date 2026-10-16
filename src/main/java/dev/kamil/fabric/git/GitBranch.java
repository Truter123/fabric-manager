package dev.kamil.fabric.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Current git branch of a directory, read from .git/HEAD without starting a git process. */
public final class GitBranch {

    private static final String REF_PREFIX = "ref: refs/heads/";

    private GitBranch() {
    }

    /** Branch name, a short commit id when HEAD is detached, or empty outside a repository. */
    public static Optional<String> of(Path directory) {
        for (var dir = directory.toAbsolutePath(); dir != null; dir = dir.getParent()) {
            var git = dir.resolve(".git");
            if (Files.isDirectory(git)) {
                return readHead(git.resolve("HEAD"));
            }
            if (Files.isRegularFile(git)) {
                return gitDir(git).flatMap(d -> readHead(d.resolve("HEAD")));
            }
        }
        return Optional.empty();
    }

    /** Worktrees and submodules have a .git file: "gitdir: <path>". */
    private static Optional<Path> gitDir(Path gitFile) {
        try {
            var line = Files.readString(gitFile).trim();
            if (!line.startsWith("gitdir:")) {
                return Optional.empty();
            }
            return Optional.of(gitFile.getParent().resolve(line.substring("gitdir:".length()).trim()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> readHead(Path head) {
        try {
            var line = Files.readString(head).trim();
            if (line.startsWith(REF_PREFIX)) {
                return Optional.of(line.substring(REF_PREFIX.length()));
            }
            return line.length() >= 7 ? Optional.of(line.substring(0, 7)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
