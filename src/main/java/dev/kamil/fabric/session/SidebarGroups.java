package dev.kamil.fabric.session;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/** Splits sessions into the pinned list and the per-project groups the sidebar shows (no JavaFX). */
final class SidebarGroups {

    private SidebarGroups() {
    }

    /** The sessions of one project folder, in their original order. */
    record Group<T>(Path project, List<T> sessions) {

        /** Folder name, or the path itself for the root. */
        String name() {
            return project.getFileName() == null ? project.toString() : project.getFileName().toString();
        }
    }

    /** Pinned sessions (listed once, never again under their project) and the projects of the others. */
    record Result<T>(List<T> pinned, List<Group<T>> projects) {
    }

    /** Groups by project folder; projects keep the order of their first session. */
    static <T> Result<T> group(List<T> sessions, Function<T, Path> directory, Predicate<T> pinned) {
        var pinnedList = new ArrayList<T>();
        var byProject = new LinkedHashMap<Path, List<T>>();
        for (var session : sessions) {
            if (pinned.test(session)) {
                pinnedList.add(session);
            } else {
                byProject.computeIfAbsent(directory.apply(session).toAbsolutePath().normalize(),
                        k -> new ArrayList<>()).add(session);
            }
        }
        var groups = new ArrayList<Group<T>>();
        byProject.forEach((dir, list) -> groups.add(new Group<>(dir, List.copyOf(list))));
        return new Result<>(List.copyOf(pinnedList), List.copyOf(groups));
    }
}
