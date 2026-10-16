package dev.kamil.fabric.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kamil.fabric.git.GitRepo.Branch;
import dev.kamil.fabric.git.GitRepo.FileChange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepoTest {

    @TempDir
    Path tmp;

    @BeforeAll
    static void requireGit() {
        var ok = false;
        try {
            ok = new ProcessBuilder("git", "--version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            // git missing
        }
        Assumptions.assumeTrue(ok, "git is not available");
    }

    private static long clock;

    private static String git(Path dir, String... args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.add("-C");
        command.add(dir.toString());
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_COMMITTER_DATE", (1_700_000_000L + clock++) + " +0000");
        var process = builder.start();
        var out = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), "git " + String.join(" ", args) + ": " + out);
        return out.strip();
    }

    private Path initRepo(String name) throws Exception {
        var dir = Files.createDirectories(tmp.resolve(name));
        git(dir, "init", "-b", "main");
        git(dir, "config", "user.name", "Test");
        git(dir, "config", "user.email", "test@example.com");
        git(dir, "config", "commit.gpgsign", "false");
        return dir;
    }

    private static void write(Path dir, String file, String content) throws IOException {
        Files.writeString(dir.resolve(file), content);
    }

    private static void commit(Path dir, String file, String content, String message) throws Exception {
        write(dir, file, content);
        git(dir, "add", "-A");
        git(dir, "commit", "-m", message);
    }

    private static GitRepo open(Path dir) {
        return GitRepo.of(dir).orElseThrow();
    }

    private static Branch local(String name) {
        return new Branch(name, false, false, null, java.time.Instant.EPOCH);
    }

    private static String current(Path dir) throws Exception {
        return git(dir, "branch", "--show-current");
    }

    @Test
    void ofIsEmptyOutsideRepositoryAndFindsRootFromSubdirectory() throws Exception {
        assertTrue(GitRepo.of(Files.createDirectories(tmp.resolve("plain"))).isEmpty());
        var dir = initRepo("repo");
        var sub = Files.createDirectories(dir.resolve("a/b"));
        assertEquals(dir.toRealPath(), GitRepo.of(sub).orElseThrow().root().toRealPath());
    }

    @Test
    void branchesMarkCurrentAndSortNewestFirst() throws Exception {
        var dir = initRepo("repo");
        commit(dir, "a.txt", "1", "c1");
        git(dir, "switch", "-c", "old");
        git(dir, "switch", "main");
        git(dir, "switch", "-c", "newer");
        commit(dir, "a.txt", "2", "c2");
        var branches = open(dir).branches();
        assertEquals(3, branches.size());
        assertEquals("newer", branches.get(0).name());
        assertTrue(branches.get(0).current());
        assertFalse(branches.get(1).current());
        assertFalse(branches.get(0).remote());
        assertNull(branches.get(0).upstream());
        assertNotNull(branches.get(0).lastCommit());
    }

    @Test
    void createBranchAndCheckoutSwitches() throws Exception {
        var dir = initRepo("repo");
        commit(dir, "a.txt", "1", "c1");
        var repo = open(dir);
        repo.createBranch("plain", false);
        assertEquals("main", current(dir));
        repo.createBranch("feature", true);
        assertEquals("feature", current(dir));
        assertThrows(GitRepo.GitException.class, () -> repo.createBranch("bad name", false));
        assertEquals(new GitRepo.Switched("main"), repo.checkout(local("main")));
        assertEquals("main", current(dir));
    }

    @Test
    void checkoutWithConflictingEditIsBlocked() throws Exception {
        var dir = initRepo("repo");
        commit(dir, "a.txt", "1", "c1");
        git(dir, "switch", "-c", "other");
        commit(dir, "a.txt", "2", "c2");
        git(dir, "switch", "main");
        write(dir, "a.txt", "local edit");
        var repo = open(dir);
        assertTrue(repo.dirty());
        var result = repo.checkout(local("other"));
        var blocked = assertInstanceOf(GitRepo.BlockedByChanges.class, result);
        assertEquals(List.of("a.txt"), blocked.files());
        assertEquals("main", current(dir));
    }

    @Test
    void stashAndCheckoutKeepsNonConflictingEdit() throws Exception {
        var dir = initRepo("repo");
        commit(dir, "a.txt", "1", "c1");
        commit(dir, "b.txt", "b", "c1b");
        git(dir, "switch", "-c", "other");
        commit(dir, "a.txt", "2", "c2");
        git(dir, "switch", "main");
        write(dir, "b.txt", "edited");
        write(dir, "new.txt", "untracked");
        var result = open(dir).stashAndCheckout(local("other"));
        assertEquals(new GitRepo.Switched("other"), result);
        assertEquals("other", current(dir));
        assertEquals("edited", Files.readString(dir.resolve("b.txt")));
        assertEquals("untracked", Files.readString(dir.resolve("new.txt")));
        assertEquals("", git(dir, "stash", "list"));
    }

    @Test
    void stashAndCheckoutKeepsStashWhenPopConflicts() throws Exception {
        var dir = initRepo("repo");
        commit(dir, "a.txt", "1", "c1");
        git(dir, "switch", "-c", "other");
        commit(dir, "a.txt", "2", "c2");
        git(dir, "switch", "main");
        write(dir, "a.txt", "local edit");
        var result = open(dir).stashAndCheckout(local("other"));
        var failed = assertInstanceOf(GitRepo.Failed.class, result);
        assertTrue(failed.message().contains("git stash list"));
        assertFalse(git(dir, "stash", "list").isEmpty());
    }

    @Test
    void forceCheckoutDiscardsChanges() throws Exception {
        var dir = initRepo("repo");
        commit(dir, "a.txt", "1", "c1");
        git(dir, "switch", "-c", "other");
        commit(dir, "a.txt", "2", "c2");
        git(dir, "switch", "main");
        write(dir, "a.txt", "local edit");
        var result = open(dir).forceCheckout(local("other"));
        assertEquals(new GitRepo.Switched("other"), result);
        assertEquals("2", Files.readString(dir.resolve("a.txt")));
    }

    @Test
    void checkoutOfRemoteBranchCreatesTrackingBranch() throws Exception {
        var origin = initRepo("origin");
        commit(origin, "a.txt", "1", "c1");
        git(origin, "switch", "-c", "feature");
        commit(origin, "a.txt", "2", "c2");
        git(origin, "switch", "main");
        var target = tmp.resolve("clone");
        var process = GitRepo.startClone(origin.toString(), target);
        process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor());
        var repo = open(target);
        var remote = repo.branches().stream().filter(b -> b.name().equals("origin/feature")).findFirst().orElseThrow();
        assertTrue(remote.remote());
        assertTrue(repo.branches().stream().noneMatch(b -> b.name().endsWith("/HEAD")));
        assertEquals(new GitRepo.Switched("feature"), repo.checkout(remote));
        assertEquals("feature", current(target));
        var tracking = repo.branches().stream().filter(b -> b.name().equals("feature")).findFirst().orElseThrow();
        assertEquals("origin/feature", tracking.upstream());
    }

    @Test
    void recentBranchesListsPreviousCheckouts() throws Exception {
        var dir = initRepo("repo");
        commit(dir, "a.txt", "1", "c1");
        git(dir, "switch", "-c", "a");
        git(dir, "switch", "main");
        var repo = open(dir);
        assertEquals(List.of("a"), repo.recentBranches(5));
        assertEquals(List.of(), repo.recentBranches(0));
    }

    @Test
    void logPagesAndShowsRefs() throws Exception {
        var dir = initRepo("repo");
        commit(dir, "a.txt", "1", "first");
        commit(dir, "a.txt", "2", "second");
        commit(dir, "a.txt", "3", "third");
        var repo = open(dir);
        var page = repo.log(1, 1);
        assertEquals(1, page.size());
        assertEquals("second", page.get(0).subject());
        assertEquals(1, page.get(0).parents().size());
        assertEquals("Test", page.get(0).author());
        assertEquals(List.of(), page.get(0).refs());
        var all = repo.log(0, 10);
        assertEquals(3, all.size());
        assertEquals("third", all.get(0).subject());
        assertTrue(all.get(0).refs().contains("HEAD -> main"));
        assertEquals(7, all.get(0).shortHash().length());
        assertTrue(all.get(2).parents().isEmpty());
        assertEquals("second", repo.message(all.get(1).hash()));
    }

    @Test
    void filesAndDiffForRootRenameAndMerge() throws Exception {
        var dir = initRepo("repo");
        var body = "line1\nline2\nline3\nline4\nline5\nline6\n";
        commit(dir, "old.txt", body, "root");
        git(dir, "mv", "old.txt", "new.txt");
        git(dir, "commit", "-m", "rename");
        var repo = open(dir);
        var log = repo.log(0, 10);
        assertEquals(List.of(new FileChange("A", "old.txt", null)), repo.files(log.get(1).hash()));
        var rename = repo.files(log.get(0).hash());
        assertEquals(List.of(new FileChange("R", "new.txt", "old.txt")), rename);
        assertEquals(1, rename.size());

        commit(dir, "new.txt", body + "added line\n", "edit");
        var edit = repo.log(0, 1).get(0);
        var change = repo.files(edit.hash()).get(0);
        assertEquals("M", change.status());
        assertTrue(repo.diff(edit.hash(), change).contains("+added line"));

        git(dir, "switch", "-c", "side");
        commit(dir, "side.txt", "side", "side");
        git(dir, "switch", "main");
        git(dir, "merge", "--no-ff", "-m", "merge", "side");
        var merge = repo.log(0, 1).get(0);
        assertEquals(2, merge.parents().size());
        assertEquals(List.of(new FileChange("A", "side.txt", null)), repo.files(merge.hash()));
        assertTrue(repo.diff(merge.hash(), new FileChange("A", "side.txt", null)).contains("+side"));
    }

    @Test
    void repoNameFollowsGit() {
        assertEquals("b", GitRepo.repoName("https://host/a/b.git"));
        assertEquals("b", GitRepo.repoName("https://host/a/b"));
        assertEquals("b", GitRepo.repoName("git@host:a/b.git"));
        assertEquals("b", GitRepo.repoName("host:b.git"));
        assertEquals("b", GitRepo.repoName("ssh://git@host/a/b.git"));
        assertEquals("b", GitRepo.repoName("/local/path/b"));
        assertEquals("b", GitRepo.repoName("/local/path/b/"));
        assertEquals("b", GitRepo.repoName("https://host/a/b.git/"));
    }

    @Test
    void logOfRevisionAndAllRefsAndInfo() throws Exception {
        var dir = initRepo("repo");
        commit(dir, "a.txt", "1", "first");
        git(dir, "switch", "-c", "feature");
        git(dir, "-c", "user.name=Other", "-c", "user.email=other@example.com", "commit", "--allow-empty", "-m", "on feature\n\nthe body");
        git(dir, "switch", "main");
        var repo = open(dir);
        assertEquals(1, repo.log("main", 0, 10).size());
        assertEquals(2, repo.log("feature", 0, 10).size());
        var all = repo.log(null, 0, 10);
        assertEquals(2, all.size());
        assertEquals("on feature", all.get(0).subject());
        assertEquals("main", repo.currentBranch());
        var info = repo.info(all.get(0).hash());
        assertEquals("other@example.com", info.authorEmail());
        assertEquals("the body", info.body());
        assertNotNull(info.commitDate());
        assertEquals("Test", repo.info(all.get(1).hash()).committer());
        git(dir, "switch", "--detach", "HEAD");
        assertNull(repo.currentBranch());
    }

    @Test
    void branchesContainingListsLocalAndRemoteWithoutHead() throws Exception {
        var origin = initRepo("origin");
        commit(origin, "a.txt", "1", "first");
        var clone = tmp.resolve("clone");
        git(tmp, "clone", origin.toString(), clone.toString());
        var repo = open(clone);
        var first = repo.log(0, 1).get(0).hash();
        git(clone, "branch", "topic");
        var names = repo.branchesContaining(first);
        assertTrue(names.containsAll(List.of("main", "topic", "origin/main")), names.toString());
        assertFalse(names.stream().anyMatch(n -> n.endsWith("HEAD")), names.toString());
        git(clone, "switch", "--detach", "HEAD");
        assertFalse(repo.branchesContaining(first).stream().anyMatch(n -> n.startsWith("(")));
        repo.createBranch("from-first", first, false);
        assertTrue(repo.branchesContaining(first).contains("from-first"));
    }

    @Test
    void listsWorktreesStashesAndTagsAndLogsThem() throws Exception {
        var dir = initRepo("wt");
        commit(dir, "a.txt", "1", "first");
        var other = tmp.resolve("wt-other");
        git(dir, "worktree", "add", "-b", "side", other.toString());
        write(dir, "a.txt", "2");
        git(dir, "stash", "push", "-m", "wip");
        git(dir, "tag", "v1");
        git(dir, "tag", "rel/v2");
        var repo = open(dir);

        var trees = repo.worktrees();
        assertEquals(2, trees.size());
        assertTrue(trees.get(0).current());
        assertEquals("main", trees.get(0).branch());
        assertFalse(trees.get(1).current());
        assertEquals("side", trees.get(1).branch());
        assertEquals(other.toRealPath(), trees.get(1).path().toRealPath());
        assertTrue(open(other).worktrees().get(1).current());

        var stashes = repo.stashes();
        assertEquals(1, stashes.size());
        assertEquals("stash@{0}", stashes.get(0).ref());
        assertTrue(stashes.get(0).message().contains("wip"));

        assertEquals(List.of("v1", "rel/v2"), repo.tags().stream().sorted(java.util.Comparator.reverseOrder()).toList());
        assertFalse(repo.log("stash@{0}", 0, 10).isEmpty());
        assertFalse(repo.log("refs/tags/rel/v2", 0, 10).isEmpty());
        assertFalse(repo.log("refs/tags/v1", 0, 10).isEmpty());
    }

    @Test
    void changesListModifiedAndUntracked() throws Exception {
        var dir = initRepo("chg");
        commit(dir, "a.txt", "1\n", "first");
        write(dir, "a.txt", "2\n");
        write(dir, "new.txt", "n\n");
        var changes = open(dir).changes();
        assertEquals(2, changes.size());
        assertTrue(changes.contains(new GitRepo.Change("a.txt", " M")));
        assertTrue(changes.contains(new GitRepo.Change("new.txt", "??")));
    }

    @Test
    void changesHandleRename() throws Exception {
        var dir = initRepo("chg-rename");
        commit(dir, "old.txt", "line1\nline2\nline3\nline4\n", "first");
        git(dir, "mv", "old.txt", "new.txt");
        write(dir, "z.txt", "z");
        var changes = open(dir).changes();
        assertEquals(List.of(new GitRepo.Change("new.txt", "R "), new GitRepo.Change("z.txt", "??")), changes);
    }

    @Test
    void diffOfModifiedAndUntrackedFile() throws Exception {
        var dir = initRepo("chg-diff");
        commit(dir, "a.txt", "1\n", "first");
        write(dir, "a.txt", "2\n");
        write(dir, "new.txt", "brand new\n");
        var repo = open(dir);
        var modified = repo.diff("a.txt");
        assertTrue(modified.contains("-1") && modified.contains("+2"), modified);
        assertTrue(repo.diff("new.txt").contains("+brand new"));
    }

    @Test
    void commitAllClearsChanges() throws Exception {
        var dir = initRepo("chg-commit");
        commit(dir, "a.txt", "1\n", "first");
        write(dir, "a.txt", "2\n");
        write(dir, "new.txt", "n\n");
        var repo = open(dir);
        repo.commitAll("both");
        assertTrue(repo.changes().isEmpty());
        assertEquals("both", git(dir, "log", "-1", "--format=%s"));
    }

    @Test
    void commitAllRejectsBlankMessage() throws Exception {
        var dir = initRepo("chg-blank");
        commit(dir, "a.txt", "1\n", "first");
        write(dir, "a.txt", "2\n");
        var repo = open(dir);
        assertThrows(GitRepo.GitException.class, () -> repo.commitAll("  "));
        assertEquals(1, repo.changes().size());
    }

    @Test
    void discardRestoresModifiedAndDeletesUntracked() throws Exception {
        var dir = initRepo("chg-discard");
        commit(dir, "a.txt", "1\n", "first");
        write(dir, "a.txt", "2\n");
        write(dir, "new.txt", "n\n");
        var repo = open(dir);
        repo.discard("a.txt");
        repo.discard("new.txt");
        assertEquals("1\n", Files.readString(dir.resolve("a.txt")));
        assertFalse(Files.exists(dir.resolve("new.txt")));
        assertTrue(repo.changes().isEmpty());
    }

    @Test
    void cloneCommandSeparatesUrlFromOptions() {
        var cmd = GitRepo.cloneCommand("--upload-pack=evil", Path.of("/tmp/t"));
        assertEquals(List.of("git", "clone", "--progress", "--", "--upload-pack=evil", "/tmp/t"), cmd);
    }

    @Test
    void addWorktreeCreatesNewBranchBesideTheRepo() throws Exception {
        var dir = initRepo("wt-new");
        commit(dir, "a.txt", "a", "first");
        var repo = GitRepo.of(dir).orElseThrow();

        var path = repo.addWorktree("feature/x");

        assertEquals(dir.resolveSibling("wt-new-worktrees").resolve("feature-x"), path);
        assertEquals("feature/x", git(path, "branch", "--show-current"));
    }

    @Test
    void addWorktreeUsesExistingBranch() throws Exception {
        var dir = initRepo("wt-existing");
        commit(dir, "a.txt", "a", "first");
        git(dir, "branch", "topic");
        var repo = GitRepo.of(dir).orElseThrow();

        var path = repo.addWorktree("topic");

        assertEquals("topic", git(path, "branch", "--show-current"));
    }

    @Test
    void addWorktreeRejectsInvalidName() throws Exception {
        var dir = initRepo("wt-invalid");
        commit(dir, "a.txt", "a", "first");
        var repo = GitRepo.of(dir).orElseThrow();

        assertThrows(GitRepo.GitException.class, () -> repo.addWorktree("bad name"));
        assertFalse(Files.exists(dir.resolveSibling("wt-invalid-worktrees")));
    }
}
