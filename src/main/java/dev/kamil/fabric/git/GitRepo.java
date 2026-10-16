package dev.kamil.fabric.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** A git repository driven through the git CLI. Methods block; call them off the UI thread. */
public final class GitRepo {

    /** git failed; message = trimmed stderr (or stdout if stderr empty). */
    public static final class GitException extends Exception {
        GitException(String message) {
            super(message);
        }
    }

    /** name: local "main", remote "origin/feature-x". */
    public record Branch(String name, boolean remote, boolean current, String upstream, Instant lastCommit) {
    }

    sealed interface CheckoutResult permits Switched, BlockedByChanges, Failed {
    }

    record Switched(String branch) implements CheckoutResult {
    }

    /** git refused because local changes would be overwritten; files = the paths git listed. */
    record BlockedByChanges(List<String> files, String message) implements CheckoutResult {
    }

    record Failed(String message) implements CheckoutResult {
    }

    public record Commit(String hash, String shortHash, List<String> parents, String author,
                  Instant date, List<String> refs, String subject) {
    }

    /** Details {@link Commit} lacks; body = message without the subject line. */
    public record CommitInfo(String authorEmail, String committer, Instant commitDate, String body) {
    }

    /** status: git name-status letter (A, M, D, R, C, T...); oldPath only for renames/copies, else null. */
    public record FileChange(String status, String path, String oldPath) {
    }

    /** A checked-out worktree; branch is the short name, null when detached. */
    public record Worktree(Path path, String branch, String head, boolean current) {
    }

    /** One stash entry; ref is like {@code stash@{0}}. */
    public record Stash(String ref, String message) {
    }

    private record Result(int code, String out, String err) {
        String message() {
            var text = err.isBlank() ? out : err;
            return text.strip();
        }
    }

    private static final long TIMEOUT_SECONDS = 60;
    private static final String FIELD = "%x1f";
    private static final String RECORD = "%x1e";
    private static final String OVERWRITE_TRACKED = "Your local changes to the following files would be overwritten by checkout:";
    private static final String OVERWRITE_UNTRACKED = "The following untracked working tree files would be overwritten by checkout:";
    private static final Pattern CHECKOUT_MOVE = Pattern.compile("^checkout: moving from (.+) to (.+)$");

    private final Path root;

    private GitRepo(Path root) {
        this.root = root;
    }

    /** Repository containing dir, or empty when dir is not inside one or git is missing. */
    public static Optional<GitRepo> of(Path dir) {
        try {
            var result = run(dir, List.of("rev-parse", "--show-toplevel"));
            if (result.code() != 0 || result.out().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new GitRepo(Path.of(result.out().strip())));
        } catch (GitException e) {
            return Optional.empty();
        }
    }

    public Path root() {
        return root;
    }

    /** Local then remote branches, each group sorted by last commit date, newest first. */
    public List<Branch> branches() throws GitException {
        var format = "%(refname)%1f%(HEAD)%1f%(upstream:short)%1f%(committerdate:iso-strict)";
        var out = ok("for-each-ref", "--sort=-committerdate", "--format=" + format, "refs/heads", "refs/remotes");
        var local = new ArrayList<Branch>();
        var remote = new ArrayList<Branch>();
        for (var line : out.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            var f = line.split("\u001f", -1);
            if (f.length < 4) {
                continue;
            }
            var ref = f[0];
            var isRemote = ref.startsWith("refs/remotes/");
            var name = ref.substring(isRemote ? "refs/remotes/".length() : "refs/heads/".length());
            if (isRemote && name.endsWith("/HEAD")) {
                continue;
            }
            var upstream = f[2].isEmpty() ? null : f[2];
            var date = f[3].isEmpty() ? Instant.EPOCH : OffsetDateTime.parse(f[3]).toInstant();
            (isRemote ? remote : local).add(new Branch(name, isRemote, "*".equals(f[1]), upstream, date));
        }
        local.addAll(remote);
        return local;
    }

    /** Worktrees of this repository (bare and prunable ones skipped), the main one first. */
    public List<Worktree> worktrees() throws GitException {
        var result = new ArrayList<Worktree>();
        Path path = null;
        String head = null;
        String branch = null;
        var skip = false;
        for (var line : (ok("worktree", "list", "--porcelain") + "\n").split("\n", -1)) {
            if (line.isEmpty()) {
                if (path != null && !skip) {
                    result.add(new Worktree(path, branch, head, sameDir(path, root)));
                }
                path = null;
                head = null;
                branch = null;
                skip = false;
            } else if (line.startsWith("worktree ")) {
                path = Path.of(line.substring("worktree ".length()));
            } else if (line.startsWith("HEAD ")) {
                head = line.substring("HEAD ".length());
            } else if (line.startsWith("branch ")) {
                var ref = line.substring("branch ".length());
                branch = ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
            } else if (line.equals("bare") || line.startsWith("prunable")) {
                skip = true;
            }
        }
        return result;
    }

    /**
     * Checks out {@code branch} (created from HEAD when it does not exist) in
     * {@code <root-parent>/<root-name>-worktrees/<branch with / as ->} and returns that folder.
     */
    public Path addWorktree(String branch) throws GitException {
        ok("check-ref-format", "--branch", branch);
        var dir = root.resolveSibling(root.getFileName() + "-worktrees").resolve(branch.replace('/', '-'));
        try {
            Files.createDirectories(dir.getParent());
        } catch (IOException e) {
            throw new GitException("Cannot create " + dir.getParent() + ": " + e.getMessage());
        }
        var exists = run(List.of("show-ref", "--verify", "--quiet", "refs/heads/" + branch)).code() == 0;
        if (exists) {
            ok("worktree", "add", dir.toString(), branch);
        } else {
            ok("worktree", "add", "-b", branch, dir.toString());
        }
        return dir;
    }

    private static boolean sameDir(Path a, Path b) {
        try {
            return a.toRealPath().equals(b.toRealPath());
        } catch (IOException e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }

    /** Stash entries, newest first. */
    public List<Stash> stashes() throws GitException {
        var result = new ArrayList<Stash>();
        for (var line : ok("stash", "list", "--format=%gd" + FIELD + "%s").split("\n")) {
            var f = line.split("\u001f", 2);
            if (f.length == 2) {
                result.add(new Stash(f[0], f[1]));
            }
        }
        return result;
    }

    /** Tag names, newest first. */
    public List<String> tags() throws GitException {
        var result = new ArrayList<String>();
        for (var line : ok("for-each-ref", "--sort=-creatordate", "--format=%(refname:short)", "refs/tags").split("\n")) {
            if (!line.isBlank()) {
                result.add(line);
            }
        }
        return result;
    }

    /** Local branches recently checked out, newest first, still existing, current excluded, at most max. */
    List<String> recentBranches(int max) throws GitException {
        if (max <= 0) {
            return List.of();
        }
        var existing = new LinkedHashSet<>(List.of(ok("for-each-ref", "--format=%(refname:short)", "refs/heads").split("\n")));
        var current = ok("branch", "--show-current");
        var found = new LinkedHashSet<String>();
        var reflog = run(List.of("reflog", "show", "HEAD", "--format=%gs"));
        if (reflog.code() != 0) {
            return List.of();
        }
        for (var line : reflog.out().split("\n")) {
            var m = CHECKOUT_MOVE.matcher(line.strip());
            if (!m.matches()) {
                continue;
            }
            var to = m.group(2);
            if (!to.equals(current) && existing.contains(to)) {
                found.add(to);
                if (found.size() >= max) {
                    break;
                }
            }
        }
        return List.copyOf(found);
    }

    boolean dirty() throws GitException {
        return !ok("status", "--porcelain").isEmpty();
    }

    /** Plain switch. Never throws for git refusals: maps them to BlockedByChanges / Failed. */
    CheckoutResult checkout(Branch branch) {
        return switchTo(branch, false);
    }

    /** Stashes local changes, switches, then pops the stash. */
    CheckoutResult stashAndCheckout(Branch branch) {
        try {
            if (!dirty()) {
                return checkout(branch);
            }
            var stash = run(List.of("stash", "push", "--include-untracked", "-m", "fabric-manager: switch to " + branch.name()));
            if (stash.code() != 0) {
                return new Failed(stash.message());
            }
            var result = checkout(branch);
            if (!(result instanceof Switched)) {
                var pop = run(List.of("stash", "pop"));
                var cause = switch (result) {
                    case BlockedByChanges b -> b.message();
                    case Failed f -> f.message();
                    case Switched s -> "";
                };
                if (pop.code() != 0) {
                    return new Failed(cause + "\nRestoring the changes failed; they are kept in the stash"
                            + " (git stash list) and must be resolved: " + pop.message());
                }
                return new Failed(cause);
            }
            var pop = run(List.of("stash", "pop"));
            if (pop.code() != 0) {
                return new Failed("Switched to " + ((Switched) result).branch() + ", but your changes conflict and are kept in the stash"
                        + " (git stash list); they must be resolved: " + pop.message());
            }
            return result;
        } catch (GitException e) {
            return new Failed(e.getMessage());
        }
    }

    /** Discards local changes: git switch --discard-changes. */
    CheckoutResult forceCheckout(Branch branch) {
        return switchTo(branch, true);
    }

    /** Validates the name, creates the branch from HEAD, optionally switching to it. */
    void createBranch(String name, boolean checkout) throws GitException {
        createBranch(name, "HEAD", checkout);
    }

    /** Validates the name, creates the branch at {@code startPoint}, optionally switching to it. */
    public void createBranch(String name, String startPoint, boolean checkout) throws GitException {
        ok("check-ref-format", "--branch", name);
        if (checkout) {
            ok("switch", "-c", name, startPoint);
        } else {
            ok("branch", name, startPoint);
        }
    }

    /** Current local branch, or null when HEAD is detached. */
    public String currentBranch() throws GitException {
        var name = ok("branch", "--show-current").strip();
        return name.isEmpty() ? null : name;
    }

    /** Local and remote branches that contain the commit, short names, without remote HEAD aliases. */
    public List<String> branchesContaining(String hash) throws GitException {
        var names = new ArrayList<String>();
        for (var line : ok("branch", "-a", "--contains", hash, "--format=%(refname)").split("\n")) {
            var ref = line.strip();
            if (ref.isEmpty() || ref.startsWith("(")) {
                continue;
            }
            var name = ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length())
                    : ref.startsWith("refs/remotes/") ? ref.substring("refs/remotes/".length()) : ref;
            if (!name.endsWith("/HEAD")) {
                names.add(name);
            }
        }
        return names;
    }

    /** Author e-mail, committer, commit date and message body of a commit. */
    public CommitInfo info(String hash) throws GitException {
        var out = ok("show", "-s", "--format=%ae" + FIELD + "%cn" + FIELD + "%cI" + FIELD + "%b", hash);
        var f = out.split("\u001f", 4);
        if (f.length < 4) {
            throw new GitException("Unexpected git output for " + hash);
        }
        return new CommitInfo(f[0], f[1], OffsetDateTime.parse(f[2]).toInstant(), f[3].strip());
    }

    /** History of HEAD, newest first, paged. Empty for a repository without commits. */
    List<Commit> log(int skip, int limit) throws GitException {
        return log("HEAD", skip, limit);
    }

    /** History of {@code revision} (null = all refs), children before parents, paged. */
    public List<Commit> log(String revision, int skip, int limit) throws GitException {
        var format = "--format=%H" + FIELD + "%P" + FIELD + "%an" + FIELD + "%aI" + FIELD + "%D" + FIELD + "%s" + RECORD;
        var result = run(List.of("log", format, "--date-order", "--skip=" + skip, "--max-count=" + limit,
                revision == null ? "--all" : revision));
        if (result.code() != 0) {
            if (result.message().contains("does not have any commits")) {
                return List.of();
            }
            throw new GitException(result.message());
        }
        var commits = new ArrayList<Commit>();
        for (var record : result.out().split("\u001e")) {
            var text = record.strip();
            if (text.isEmpty()) {
                continue;
            }
            var f = text.split("\u001f", -1);
            if (f.length < 6) {
                continue;
            }
            commits.add(new Commit(f[0], f[0].substring(0, Math.min(7, f[0].length())), splitOn(f[1], " "), f[2],
                    OffsetDateTime.parse(f[3]).toInstant(), splitOn(f[4], ", "), f[5]));
        }
        return commits;
    }

    /** Full commit message. */
    String message(String hash) throws GitException {
        return ok("show", "-s", "--format=%B", hash);
    }

    /** Changed files of a commit; for merges the diff against the first parent. */
    public List<FileChange> files(String hash) throws GitException {
        var out = ok("-c", "core.quotepath=false", "show", "--name-status", "-M", "--format=", "--root", "-m", "--first-parent", hash);
        var changes = new ArrayList<FileChange>();
        for (var line : out.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            var f = line.split("\t");
            var status = f[0].substring(0, 1);
            if (f.length >= 3 && (status.equals("R") || status.equals("C"))) {
                changes.add(new FileChange(status, f[2], f[1]));
            } else if (f.length >= 2) {
                changes.add(new FileChange(status, f[1], null));
            }
        }
        return changes;
    }

    /** Unified diff of one file in one commit; for merges against the first parent. */
    public String diff(String hash, FileChange file) throws GitException {
        var args = new ArrayList<>(List.of("-c", "core.quotepath=false", "show", "--format=", "-M", "--root", "-m", "--first-parent", hash, "--", file.path()));
        if (file.oldPath() != null) {
            args.add(file.oldPath());
        }
        return ok(args.toArray(String[]::new));
    }

    /** Working tree changes: status is git's two porcelain letters (index, worktree), "??" for untracked. */
    public record Change(String path, String status) {
        public boolean untracked() {
            return "??".equals(status);
        }
    }

    /** Uncommitted changes (staged, unstaged, untracked); a rename lists the new path. */
    public List<Change> changes() throws GitException {
        var out = ok("status", "--porcelain=v1", "-z", "--untracked-files=all");
        var result = new ArrayList<Change>();
        var parts = out.split("\0");
        for (var i = 0; i < parts.length; i++) {
            var entry = parts[i];
            if (entry.length() < 4) {
                continue;
            }
            var status = entry.substring(0, 2);
            result.add(new Change(entry.substring(3), status));
            if (status.charAt(0) == 'R' || status.charAt(0) == 'C' || status.charAt(1) == 'R' || status.charAt(1) == 'C') {
                i++;
            }
        }
        return result;
    }

    /** Diff of one file against HEAD; an untracked file is shown as all added lines. */
    public String diff(String path) throws GitException {
        var tracked = run(List.of("ls-files", "--error-unmatch", "--", path)).code() == 0;
        if (tracked) {
            var hasHead = run(List.of("rev-parse", "--verify", "--quiet", "HEAD")).code() == 0;
            return hasHead ? ok("-c", "core.quotepath=false", "diff", "HEAD", "--", path)
                    : ok("-c", "core.quotepath=false", "diff", "--cached", "--", path);
        }
        var result = run(List.of("-c", "core.quotepath=false", "diff", "--no-index", "--", "/dev/null", path));
        if (result.code() > 1) {
            throw new GitException(result.message());
        }
        return result.out();
    }

    /** Stages everything and commits it; a blank message is rejected. */
    public void commitAll(String message) throws GitException {
        if (message == null || message.isBlank()) {
            throw new GitException("Commit message is empty");
        }
        ok("add", "-A");
        ok("commit", "-m", message.strip());
    }

    /** Throws away the changes of one file: tracked files are restored, untracked ones deleted. */
    public void discard(String path) throws GitException {
        var target = root.resolve(path).normalize();
        if (!target.startsWith(root.normalize()) || target.equals(root.normalize())) {
            throw new GitException("Path is outside the repository: " + path);
        }
        var tracked = run(List.of("ls-files", "--error-unmatch", "--", path)).code() == 0;
        if (!tracked) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException e) {
                throw new GitException("Cannot delete " + path + ": " + e.getMessage());
            }
            return;
        }
        var inHead = run(List.of("cat-file", "-e", "HEAD:" + path)).code() == 0;
        if (inHead) {
            ok("restore", "--source=HEAD", "--staged", "--worktree", "--", path);
        } else {
            ok("rm", "-f", "--", path);
        }
    }

    /** Folder name a clone of url gets, like git does. */
    static String repoName(String url) {
        var s = stripTrailing(url.strip());
        if (s.endsWith(".git")) {
            s = stripTrailing(s.substring(0, s.length() - ".git".length()));
        }
        var cut = Math.max(s.lastIndexOf('/'), s.lastIndexOf(':'));
        return s.substring(cut + 1);
    }

    /** The clone argv; "--" keeps a url that starts with "-" from being read as an option. */
    static List<String> cloneCommand(String url, Path target) {
        return List.of("git", "clone", "--progress", "--", url, target.toString());
    }

    /** Starts git clone with stderr merged into stdout so the caller can stream progress; does not wait. */
    static Process startClone(String url, Path target) throws IOException {
        var builder = new ProcessBuilder(cloneCommand(url, target));
        builder.redirectErrorStream(true);
        configure(builder);
        return builder.start();
    }

    private static String stripTrailing(String s) {
        var end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    private static List<String> splitOn(String value, String separator) {
        if (value.isEmpty()) {
            return List.of();
        }
        return List.of(value.split(Pattern.quote(separator)));
    }

    private CheckoutResult switchTo(Branch branch, boolean discard) {
        try {
            var args = new ArrayList<String>();
            args.add("switch");
            if (discard) {
                args.add("--discard-changes");
            }
            var target = branch.name();
            if (branch.remote()) {
                var local = target.substring(target.indexOf('/') + 1);
                var exists = run(List.of("show-ref", "--verify", "--quiet", "refs/heads/" + local)).code() == 0;
                if (exists) {
                    target = local;
                } else {
                    args.add("--track");
                    args.add(target);
                    target = local;
                }
            }
            if (!args.contains("--track")) {
                args.add(target);
            }
            var result = run(args);
            if (result.code() == 0) {
                return new Switched(target);
            }
            var files = overwrittenFiles(result.err());
            if (!files.isEmpty()) {
                return new BlockedByChanges(files, result.message());
            }
            return new Failed(result.message());
        } catch (GitException e) {
            return new Failed(e.getMessage());
        }
    }

    private static List<String> overwrittenFiles(String stderr) {
        var files = new ArrayList<String>();
        var collecting = false;
        for (var line : stderr.split("\n")) {
            if (line.contains(OVERWRITE_TRACKED) || line.contains(OVERWRITE_UNTRACKED)) {
                collecting = true;
            } else if (collecting && line.startsWith("\t")) {
                files.add(line.strip());
            } else {
                collecting = false;
            }
        }
        return files;
    }

    /** Runs git in the repository and returns stdout, or throws with git's message on a non-zero exit. */
    private String ok(String... args) throws GitException {
        var result = run(List.of(args));
        if (result.code() != 0) {
            throw new GitException(result.message().isEmpty() ? "git " + String.join(" ", args) + " failed" : result.message());
        }
        return result.out();
    }

    private Result run(List<String> args) throws GitException {
        return run(root, args);
    }

    private static Result run(Path dir, List<String> args) throws GitException {
        var command = new ArrayList<String>();
        command.add("git");
        command.add("-C");
        command.add(dir.toString());
        command.addAll(args);
        Path outFile = null;
        Path errFile = null;
        try {
            outFile = Files.createTempFile("fabric-git", ".out");
            errFile = Files.createTempFile("fabric-git", ".err");
            var builder = new ProcessBuilder(command);
            builder.redirectOutput(outFile.toFile());
            builder.redirectError(errFile.toFile());
            builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
            configure(builder);
            var process = builder.start();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new GitException("git " + String.join(" ", args) + " timed out");
            }
            return new Result(process.exitValue(), stripNewlines(Files.readString(outFile, StandardCharsets.UTF_8)),
                    stripNewlines(Files.readString(errFile, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new GitException("Cannot run git: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitException("git " + String.join(" ", args) + " interrupted");
        } finally {
            deleteQuietly(outFile);
            deleteQuietly(errFile);
        }
    }

    private static void configure(ProcessBuilder builder) {
        var env = builder.environment();
        env.put("LC_ALL", "C");
        env.put("GIT_TERMINAL_PROMPT", "0");
        if (System.getenv("GIT_SSH_COMMAND") == null) {
            env.put("GIT_SSH_COMMAND", "ssh -o BatchMode=yes");
        }
    }

    private static String stripNewlines(String s) {
        var end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }

    private static void deleteQuietly(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // temp file; nothing to do
            }
        }
    }
}
