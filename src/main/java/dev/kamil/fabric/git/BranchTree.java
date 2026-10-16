package dev.kamil.fabric.git;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/** Pure model of the history window's branch tree: branch names grouped into folders by "/". */
public final class BranchTree {

    /** Ref of the "All branches" node. */
    public static final String ALL = "\u0000all";
    /** Ref of the HEAD node. */
    static final String HEAD = "HEAD";

    /** A tree node; ref is the revision to show (null for folders and group headers), worktree its folder if any. */
    public record Node(String label, String ref, List<Node> children, Path worktree) {
        public Node(String label, String ref, List<Node> children) {
            this(label, ref, children, null);
        }
    }

    private BranchTree() {
    }

    /** Folders first (sorted), then the plain names in the given order; {@code refPrefix} is prepended to each ref. */
    static List<Node> group(List<String> names, String refPrefix) {
        var folders = new TreeMap<String, List<String>>();
        var leaves = new ArrayList<Node>();
        for (var name : names) {
            var slash = name.indexOf('/');
            if (slash < 0) {
                leaves.add(new Node(name, refPrefix + name, List.of()));
            } else {
                folders.computeIfAbsent(name.substring(0, slash), k -> new ArrayList<>()).add(name.substring(slash + 1));
            }
        }
        var result = new ArrayList<Node>();
        folders.forEach((folder, rest) -> result.add(new Node(folder, null, group(rest, refPrefix + folder + "/"))));
        result.addAll(leaves);
        return result;
    }

    /**
     * Top-level nodes: All branches, HEAD, Local, and a "Remote" group with one node per remote. Branches not
     * containing {@code filter} (case-insensitive) are left out, and so are groups that end up empty.
     */
    public static List<Node> tree(List<GitRepo.Branch> branches, String current, String filter) {
        return tree(branches, current, filter, List.of(), List.of(), List.of());
    }

    /**
     * Like the 3-argument form, plus "Worktrees" (only with 2+), "Stashes" and "Tags" groups after "Remote"; the
     * filter applies to them too.
     */
    public static List<Node> tree(List<GitRepo.Branch> branches, String current, String filter,
            List<GitRepo.Worktree> worktrees, List<GitRepo.Stash> stashes, List<String> tags) {
        var needle = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
        var local = new ArrayList<String>();
        var remotes = new TreeMap<String, List<String>>();
        for (var b : branches) {
            if (!b.name().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            if (b.remote()) {
                var slash = b.name().indexOf('/');
                remotes.computeIfAbsent(b.name().substring(0, slash < 0 ? b.name().length() : slash), k -> new ArrayList<>())
                        .add(slash < 0 ? b.name() : b.name().substring(slash + 1));
            } else {
                local.add(b.name());
            }
        }
        var top = new ArrayList<Node>();
        top.add(new Node("All branches", ALL, List.of()));
        top.add(new Node("HEAD (" + (current == null ? "detached" : "current: " + current) + ")", HEAD, List.of()));
        if (!local.isEmpty()) {
            top.add(new Node("Local", null, group(local, "")));
        }
        if (!remotes.isEmpty()) {
            var nodes = new ArrayList<Node>();
            remotes.forEach((remote, names) -> nodes.add(new Node(remote, null, group(names, remote + "/"))));
            top.add(new Node("Remote", null, nodes));
        }
        if (worktrees.size() >= 2) {
            var nodes = new ArrayList<Node>();
            for (var w : worktrees) {
                var folder = w.path().getFileName() == null ? w.path().toString() : w.path().getFileName().toString();
                var name = w.branch() != null ? w.branch() : w.head() == null ? "?" : w.head().substring(0, Math.min(7, w.head().length()));
                var label = name + " — " + folder + (w.current() ? " (this)" : "");
                if (label.toLowerCase(Locale.ROOT).contains(needle)) {
                    nodes.add(new Node(label, w.branch() != null ? w.branch() : w.head(), List.of(), w.path()));
                }
            }
            if (!nodes.isEmpty()) {
                top.add(new Node("Worktrees", null, nodes));
            }
        }
        var stashNodes = new ArrayList<Node>();
        for (var s : stashes) {
            var label = s.ref() + ": " + s.message();
            if (label.toLowerCase(Locale.ROOT).contains(needle)) {
                stashNodes.add(new Node(label, s.ref(), List.of()));
            }
        }
        if (!stashNodes.isEmpty()) {
            top.add(new Node("Stashes", null, stashNodes));
        }
        var shownTags = tags.stream().filter(t -> t.toLowerCase(Locale.ROOT).contains(needle)).toList();
        if (!shownTags.isEmpty()) {
            top.add(new Node("Tags", null, group(shownTags, "refs/tags/")));
        }
        return top;
    }
}
