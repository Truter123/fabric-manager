package dev.kamil.fabric.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChangesTreeTest {

    private static GitRepo.FileChange f(String path) {
        return new GitRepo.FileChange("M", path, null);
    }

    @Test
    void collapsesSingleChildFolderChains() {
        var nodes = ChangesTree.build(List.of(f("src/main/java/dev/Foo.java"), f("src/main/java/dev/Bar.java")));
        assertEquals(1, nodes.size());
        assertEquals("src/main/java/dev", nodes.get(0).label());
        assertEquals(List.of("Bar.java", "Foo.java"), nodes.get(0).children().stream().map(ChangesTree.Node::label).toList());
    }

    @Test
    void branchingFoldersStopTheCollapse() {
        var nodes = ChangesTree.build(List.of(f("README.md"), f("src/a/X.java"), f("src/b/Y.java"), f("src/Z.java")));
        assertEquals(List.of("src", "README.md"), nodes.stream().map(ChangesTree.Node::label).toList());
        var src = nodes.get(0);
        assertEquals(List.of("a", "b", "Z.java"), src.children().stream().map(ChangesTree.Node::label).toList());
        assertNull(src.file());
        assertEquals("src/a/X.java", src.children().get(0).children().get(0).file().path());
    }

    @Test
    void firstFileFollowsTreeOrder() {
        var nodes = ChangesTree.build(List.of(f("README.md"), f("src/a/X.java")));
        assertEquals("src/a/X.java", ChangesTree.first(nodes).path());
        assertNull(ChangesTree.first(List.of()));
    }
}
