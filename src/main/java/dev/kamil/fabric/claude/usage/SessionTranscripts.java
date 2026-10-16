package dev.kamil.fabric.claude.usage;

import dev.kamil.fabric.claude.ClaudeEvents;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Finds the transcript file of a session: the path its hooks reported, else the newest one of its project. */
public final class SessionTranscripts {

    private SessionTranscripts() {
    }

    /** Directory name Claude Code uses for a working directory: every '/' and '.' becomes '-'. */
    public static String projectDirName(String cwd) {
        return cwd.replace('/', '-').replace('.', '-');
    }

    public static Optional<Path> find(Path eventsDir, String session, Path cwd) {
        return find(eventsDir, session, cwd, Path.of(System.getProperty("user.home"), ".claude/projects"));
    }

    static Optional<Path> find(Path eventsDir, String session, Path cwd, Path projectsDir) {
        var reported = ClaudeEvents.transcript(eventsDir, session).filter(Files::isRegularFile);
        return reported.isPresent() ? reported : newest(projectsDir.resolve(projectDirName(cwd.toString())));
    }

    static Optional<Path> newest(Path dir) {
        try (var files = Files.list(dir)) {
            return files.filter(f -> f.toString().endsWith(".jsonl")).max(java.util.Comparator.comparing(f -> {
                try {
                    return Files.getLastModifiedTime(f);
                } catch (IOException e) {
                    return java.nio.file.attribute.FileTime.fromMillis(0);
                }
            }));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
