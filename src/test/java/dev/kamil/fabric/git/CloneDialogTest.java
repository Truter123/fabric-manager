package dev.kamil.fabric.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CloneDialogTest {

    @Test
    void optionLikeUrlIsRejected() {
        assertTrue(CloneDialog.looksLikeOption(" --upload-pack=evil"));
        assertFalse(CloneDialog.looksLikeOption("https://github.com/o/r"));
    }

    @Test
    void parsesReceivingAndResolving() {
        assertEquals(0.36, CloneDialog.progress("Receiving objects:  45% (9/20)").getAsDouble(), 1e-9);
        assertEquals(1.0, CloneDialog.progress("Resolving deltas: 100% (3/3), done.").getAsDouble(), 1e-9);
        assertEquals(0.9, CloneDialog.progress("Resolving deltas:  50% (1/2)").getAsDouble(), 1e-9);
        assertEquals(0.0, CloneDialog.progress("remote: Receiving objects:   0% (0/5)".substring(8)).getAsDouble(), 1e-9);
    }

    @Test
    void ignoresOtherLines() {
        assertTrue(CloneDialog.progress("Cloning into 'x'...").isEmpty());
        assertTrue(CloneDialog.progress("remote: Counting objects: 50% (1/2)").isEmpty());
    }

    @Test
    void detectsGitUrls() {
        assertTrue(CloneDialog.looksLikeGitUrl("https://github.com/o/r"));
        assertTrue(CloneDialog.looksLikeGitUrl("git@github.com:o/r.git"));
        assertTrue(CloneDialog.looksLikeGitUrl("ssh://host/r"));
        assertTrue(CloneDialog.looksLikeGitUrl("/tmp/repo.git"));
        assertFalse(CloneDialog.looksLikeGitUrl("hello world"));
        assertFalse(CloneDialog.looksLikeGitUrl(""));
        assertFalse(CloneDialog.looksLikeGitUrl(null));
    }

    @Test
    void detectsAuthHints() {
        assertTrue(CloneDialog.needsAuthHint("fatal: Authentication failed for 'x'"));
        assertTrue(CloneDialog.needsAuthHint("fatal: could not read Username for 'https://x'"));
        assertTrue(CloneDialog.needsAuthHint("git@x: Permission denied (publickey)."));
        assertTrue(CloneDialog.needsAuthHint("terminal prompts disabled"));
        assertFalse(CloneDialog.needsAuthHint("fatal: repository not found"));
    }

    @Test
    void clonesLocalRepository(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(run(tmp, "git", "--version") == 0, "git missing");
        var src = Files.createDirectory(tmp.resolve("src"));
        assertEquals(0, run(src, "git", "init", "-q"));
        Files.writeString(src.resolve("a.txt"), "hi");
        assertEquals(0, run(src, "git", "add", "a.txt"));
        assertEquals(0, run(src, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "one"));

        var target = tmp.resolve("copy");
        var out = new ArrayList<String>();
        var code = CloneDialog.readOutput(GitRepo.startClone(src.toString(), target), (l, cr) -> out.add(l));
        assertEquals(0, code, String.join("\n", out));
        assertTrue(Files.isDirectory(target));
        assertTrue(Files.exists(target.resolve("a.txt")));
    }

    @Test
    void splitsOnCarriageReturns() throws Exception {
        Assumptions.assumeTrue(run(Path.of("."), "printf", "x") == 0);
        var p = new ProcessBuilder("printf", "a\\rb\\nc").redirectErrorStream(true).start();
        List<String> got = new ArrayList<>();
        var code = CloneDialog.readOutput(p, (l, cr) -> got.add(l + (cr ? "|cr" : "")));
        assertEquals(0, code);
        assertEquals(List.of("a|cr", "b", "c"), got);
    }

    private static int run(Path dir, String... cmd) throws Exception {
        var p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor(30, TimeUnit.SECONDS);
        return p.exitValue();
    }
}
