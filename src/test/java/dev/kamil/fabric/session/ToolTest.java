package dev.kamil.fabric.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolTest {

    @TempDir
    Path tmp;

    private void fake(String name, boolean executable) throws IOException {
        var f = Files.writeString(tmp.resolve(name), "#!/bin/sh\n");
        assertTrue(f.toFile().setExecutable(executable));
    }

    @Test
    void availableListsInstalledToolsThenShell() throws IOException {
        fake("claude", true);
        fake("aider", true);
        fake("codex", false);
        var names = Tool.available(tmp.toString()).stream().map(Tool::name).toList();
        assertEquals(List.of("claude", "aider", "shell"), names);
    }

    @Test
    void shellAlwaysIncluded() {
        assertEquals(List.of(Tool.SHELL), Tool.available(tmp.toString()));
        assertEquals(List.of(Tool.SHELL), Tool.available(null));
    }

    @Test
    void customKeepsCommandLine() {
        var tool = Tool.custom("claude --resume");
        assertEquals("custom", tool.name());
        assertEquals("claude --resume", tool.command());
    }

    @Test
    void byNameFindsBuiltIns() {
        assertEquals("gemini", Tool.byName("gemini").orElseThrow().command());
        assertTrue(Tool.byName("nope").isEmpty());
    }

    @org.junit.jupiter.api.Test
    void recognisesSshTools() {
        assertTrue(new Tool("ssh prod", "ssh 'prod'").isSsh());
        assertTrue(!new Tool("claude", "claude").isSsh());
        assertTrue(!Tool.SHELL.isSsh());
        assertTrue(!new Tool("custom", "sshfs a b").isSsh());
    }
}
