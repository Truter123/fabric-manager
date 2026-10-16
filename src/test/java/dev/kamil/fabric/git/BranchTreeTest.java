package dev.kamil.fabric.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BranchTreeTest {

    private static GitRepo.Branch b(String name, boolean remote) {
        return new GitRepo.Branch(name, remote, false, null, Instant.EPOCH);
    }

    @Test
    void groupsSlashesIntoFoldersFoldersFirst() {
        var nodes = BranchTree.group(List.of("main", "feature/a", "feature/b", "fix/deep/c"), "");
        assertEquals(List.of("feature", "fix", "main"), nodes.stream().map(BranchTree.Node::label).toList());
        var feature = nodes.get(0);
        assertEquals(null, feature.ref());
        assertEquals(List.of("feature/a", "feature/b"), feature.children().stream().map(BranchTree.Node::ref).toList());
        assertEquals("deep", nodes.get(1).children().get(0).label());
        assertEquals("fix/deep/c", nodes.get(1).children().get(0).children().get(0).ref());
        assertEquals("main", nodes.get(2).ref());
    }

    @Test
    void treeHasAllHeadLocalAndRemotes() {
        var top = BranchTree.tree(List.of(b("main", false), b("origin/main", true), b("origin/feature/x", true),
                b("up/dev", true)), "main", "");
        assertEquals(List.of("All branches", "HEAD (current: main)", "Local", "Remote"),
                top.stream().map(BranchTree.Node::label).toList());
        assertEquals(BranchTree.ALL, top.get(0).ref());
        assertEquals("HEAD", top.get(1).ref());
        var remote = top.get(3);
        assertEquals(List.of("origin", "up"), remote.children().stream().map(BranchTree.Node::label).toList());
        var origin = remote.children().get(0);
        assertEquals(List.of("feature", "main"), origin.children().stream().map(BranchTree.Node::label).toList());
        assertEquals("origin/feature/x", origin.children().get(0).children().get(0).ref());
        assertEquals("origin/main", origin.children().get(1).ref());
    }

    @Test
    void filterDropsEmptyGroupsAndDetachedHeadIsLabelled() {
        var top = BranchTree.tree(List.of(b("main", false), b("origin/dev", true)), null, "DEV");
        assertEquals(List.of("All branches", "HEAD (detached)", "Remote"), top.stream().map(BranchTree.Node::label).toList());
    }

    private static List<BranchTree.Node> extra(String filter, List<GitRepo.Worktree> trees) {
        return BranchTree.tree(List.of(b("main", false), b("origin/main", true)), "main", filter, trees,
                List.of(new GitRepo.Stash("stash@{0}", "wip on main"), new GitRepo.Stash("stash@{1}", "other")),
                List.of("v2", "rel/v1"));
    }

    private static final List<GitRepo.Worktree> TREES = List.of(
            new GitRepo.Worktree(Path.of("/w/app"), "main", "abcdef1234", true),
            new GitRepo.Worktree(Path.of("/w/app-fix"), null, "1234567890", false));

    private static List<String> labels(List<BranchTree.Node> nodes) {
        return nodes.stream().map(BranchTree.Node::label).toList();
    }

    @Test
    void newGroupsFollowRemoteInOrder() {
        assertEquals(List.of("All branches", "HEAD (current: main)", "Local", "Remote", "Worktrees", "Stashes", "Tags"),
                labels(extra("", TREES)));
    }

    @Test
    void worktreesHiddenWithSingleWorktree() {
        assertEquals(List.of("All branches", "HEAD (current: main)", "Local", "Remote", "Stashes", "Tags"),
                labels(extra("", TREES.subList(0, 1))));
    }

    @Test
    void worktreeNodesUseBranchOrShortHashAndCarryPath() {
        var nodes = extra("", TREES).get(4).children();
        assertEquals("main — app (this)", nodes.get(0).label());
        assertEquals("main", nodes.get(0).ref());
        assertEquals(Path.of("/w/app"), nodes.get(0).worktree());
        assertEquals("1234567 — app-fix", nodes.get(1).label());
        assertEquals("1234567890", nodes.get(1).ref());
    }

    @Test
    void stashAndTagNodes() {
        var top = extra("", TREES);
        var stashes = top.get(5).children();
        assertEquals("stash@{0}: wip on main", stashes.get(0).label());
        assertEquals("stash@{0}", stashes.get(0).ref());
        var tags = top.get(6).children();
        assertEquals(List.of("rel", "v2"), labels(tags));
        assertEquals("refs/tags/rel/v1", tags.get(0).children().get(0).ref());
        assertEquals("refs/tags/v2", tags.get(1).ref());
    }

    @Test
    void filterAppliesToWorktreesStashesAndTags() {
        assertEquals(List.of("All branches", "HEAD (current: main)", "Stashes"), labels(extra("OTHER", TREES)));
        assertEquals(List.of("All branches", "HEAD (current: main)", "Worktrees"), labels(extra("app-fix", TREES)));
        var tags = extra("v1", TREES);
        assertEquals("Tags", tags.get(tags.size() - 1).label());
        assertEquals(1, tags.get(tags.size() - 1).children().size());
    }
}
