package dev.kamil.fabric.processes;

/** One docker container as listed by {@code docker ps -a}. */
public record DockerContainer(String id, String name, String image, String status, String ports, boolean running) {
}
