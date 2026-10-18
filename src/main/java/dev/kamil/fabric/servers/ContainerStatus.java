package dev.kamil.fabric.servers;

import java.time.Instant;

/** One container; {@code health} is null when the image defines no health check. */
public record ContainerStatus(String name, String state, Instant startedAt, String health, String image) {
}
