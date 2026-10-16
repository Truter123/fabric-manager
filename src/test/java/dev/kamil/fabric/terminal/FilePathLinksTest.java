package dev.kamil.fabric.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilePathLinksTest {

    @Test
    void relativePathWithLine() {
        var line = "error in src/a/B.java:12 here";
        var m = FilePathLinks.find(line).get(0);
        assertEquals("src/a/B.java", m.path());
        assertEquals(12, m.line());
        assertEquals("src/a/B.java:12", line.substring(m.start(), m.end()));
    }

    @Test
    void dotSlashWithoutLine() {
        var m = FilePathLinks.find("run ./run.sh now").get(0);
        assertEquals("./run.sh", m.path());
        assertEquals(0, m.line());
    }

    @Test
    void absolutePathWithLineAndColumn() {
        var m = FilePathLinks.find("/tmp/x.txt:3:7").get(0);
        assertEquals("/tmp/x.txt", m.path());
        assertEquals(3, m.line());
        assertEquals(7, m.column());
    }

    @Test
    void urlIsNotAPath() {
        assertTrue(FilePathLinks.find("see https://example.com/a/b.html ok").isEmpty());
    }

    @Test
    void sentenceDotIsNotPartOfThePath() {
        assertEquals("src/a.txt", FilePathLinks.find("edited src/a.txt.").get(0).path());
    }

    @Test
    void stackTraceStyleBareFileName(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Foo.java"), "x");
        var result = new FilePathLinks(dir).apply("at dev.kamil.Foo.bar(Foo.java:42)");
        assertNotNull(result);
        assertEquals(1, result.getItems().size());
    }

    @Test
    void onlyExistingFilesAreLinked(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/a"));
        Files.writeString(dir.resolve("src/a/B.java"), "x");
        var filter = new FilePathLinks(dir);
        var result = filter.apply("src/a/B.java:12 and src/a/Missing.java:3");
        assertEquals(1, result.getItems().size());
        assertEquals(0, result.getItems().get(0).getStartOffset());
        assertNull(filter.apply("src/a/Missing.java:3"));
    }

    @Test
    void commandUsesIdeaWithLineOrXdgOpen() {
        var file = Path.of("/tmp/x.txt");
        assertEquals(List.of("/i/idea", "--line", "5", "/tmp/x.txt"), FilePathLinks.command("/i/idea", file, 5));
        assertEquals(List.of("/i/idea", "/tmp/x.txt"), FilePathLinks.command("/i/idea", file, 0));
        assertEquals(List.of("xdg-open", "/tmp/x.txt"), FilePathLinks.command(null, file, 5));
    }
}
