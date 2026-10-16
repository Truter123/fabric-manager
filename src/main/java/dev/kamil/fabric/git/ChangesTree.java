package dev.kamil.fabric.git;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/** Pure model of a commit's changed files as a directory tree; single-child folder chains are collapsed. */
public final class ChangesTree {

    /** A folder (file null, label = possibly collapsed path "a/b/c") or a file (label = file name). */
    public record Node(String label, GitRepo.FileChange file, List<Node> children) {
    }

    private static final class Dir {
        final TreeMap<String, Dir> dirs = new TreeMap<>();
        final List<GitRepo.FileChange> files = new ArrayList<>();
    }

    private ChangesTree() {
    }

    public static List<Node> build(List<GitRepo.FileChange> changes) {
        var root = new Dir();
        for (var change : changes) {
            var parts = change.path().split("/");
            var dir = root;
            for (var i = 0; i < parts.length - 1; i++) {
                dir = dir.dirs.computeIfAbsent(parts[i], k -> new Dir());
            }
            dir.files.add(change);
        }
        return convert(root);
    }

    private static List<Node> convert(Dir dir) {
        var nodes = new ArrayList<Node>();
        dir.dirs.forEach((name, sub) -> {
            var label = name;
            var kids = convert(sub);
            while (kids.size() == 1 && kids.get(0).file() == null) {
                label = label + "/" + kids.get(0).label();
                kids = kids.get(0).children();
            }
            nodes.add(new Node(label, null, kids));
        });
        dir.files.stream().sorted(Comparator.comparing(ChangesTree::name))
                .forEach(f -> nodes.add(new Node(name(f), f, List.of())));
        return nodes;
    }

    private static String name(GitRepo.FileChange change) {
        return change.path().substring(change.path().lastIndexOf('/') + 1);
    }

    /** First file in tree order, or null. */
    public static GitRepo.FileChange first(List<Node> nodes) {
        for (var node : nodes) {
            var found = node.file() != null ? node.file() : first(node.children());
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
