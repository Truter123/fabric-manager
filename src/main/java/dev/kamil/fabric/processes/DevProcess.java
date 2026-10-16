package dev.kamil.fabric.processes;

import java.util.List;

/** A developer's Java or Node process. */
public record DevProcess(long pid, Kind kind, String name, String cwd, List<Integer> ports,
        boolean inContainer, String commandLine) {

    public enum Kind { JAVA, NODE }
}
