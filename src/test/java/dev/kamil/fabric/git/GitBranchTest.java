package dev.kamil.fabric.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitBranchTest {

    @TempDir
    Path tmp;

    private Path repo(String head) throws IOException {
        var repo = tmp.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve(".git/HEAD"), head);
        return repo;
    }

    @Test
    void branchFromHead() throws IOException {
        assertEquals(Optional.of("feature/login"), GitBranch.of(repo("ref: refs/heads/feature/login\n")));
    }

    @Test
    void subdirectoryFindsRepoAbove() throws IOException {
        var sub = Files.createDirectories(repo("ref: refs/heads/main\n").resolve("src/main"));
        assertEquals(Optional.of("main"), GitBranch.of(sub));
    }

    @Test
    void detachedHeadShowsShortCommit() throws IOException {
        assertEquals(Optional.of("576fce8"), GitBranch.of(repo("576fce8a1b2c3d4e5f60718293a4b5c6d7e8f901\n")));
    }

    @Test
    void worktreeFollowsGitdirFile() throws IOException {
        var gitDir = Files.createDirectories(tmp.resolve("main/.git/worktrees/wt"));
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/wt-branch\n");
        var worktree = Files.createDirectories(tmp.resolve("wt"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + gitDir + "\n");
        assertEquals(Optional.of("wt-branch"), GitBranch.of(worktree));
    }

    @Test
    void outsideRepoIsEmpty() throws IOException {
        assertTrue(GitBranch.of(Files.createDirectories(tmp.resolve("plain"))).isEmpty());
    }
}
