package dev.kamil.fabric.processes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class DevProcessesTest {

    @Test
    void jarName() {
        assertEquals("app.jar", DevProcesses.nameFor(List.of("java", "-jar", "x/app.jar")));
    }

    @Test
    void mainClassAfterClasspathAndOptions() {
        assertEquals("com.foo.Main", DevProcesses.nameFor(
                List.of("/usr/bin/java", "-cp", "a.jar:b.jar", "-Dx=1", "com.foo.Main", "arg")));
    }

    @Test
    void moduleMainClass() {
        assertEquals("com.x.App", DevProcesses.nameFor(
                List.of("java", "--add-opens=a=b", "-m", "mod/com.x.App")));
    }

    @Test
    void angularCli() {
        assertEquals("ng serve --port 4200", DevProcesses.nameFor(
                List.of("node", "/path/@angular/cli/bin/ng", "serve", "--port", "4200")));
    }

    @Test
    void nodeScriptBasename() {
        assertEquals("brave-search-mcp-server", DevProcesses.nameFor(
                List.of("node", "/x/brave-search-mcp-server", "--enabled-tools", "y")));
    }

    @Test
    void parsesListeningPorts() {
        var ports = DevProcesses.parsePorts(List.of(
                "LISTEN 0 4096 *:8080 *:* users:((\"java\",pid=1234,fd=55))",
                "LISTEN 0 511 127.0.0.1:4200 0.0.0.0:* users:((\"node\",pid=99,fd=20))",
                "LISTEN 0 4096 [::]:8081 [::]:* users:((\"java\",pid=1234,fd=56))",
                "LISTEN 0 128 0.0.0.0:22 0.0.0.0:*"));

        assertEquals(List.of(8080, 8081), ports.get(1234L));
        assertEquals(List.of(4200), ports.get(99L));
        assertEquals(2, ports.size());
    }
}
