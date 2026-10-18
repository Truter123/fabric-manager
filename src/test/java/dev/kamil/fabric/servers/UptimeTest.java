package dev.kamil.fabric.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class UptimeTest {

    @Test
    void formatsDaysHoursAndMinutes() {
        assertEquals("3d 4h", Uptime.format(Duration.ofHours(76).plusMinutes(30)));
        assertEquals("5h 12m", Uptime.format(Duration.ofMinutes(312)));
        assertEquals("42m", Uptime.format(Duration.ofMinutes(42)));
        assertEquals("0m", Uptime.format(Duration.ofSeconds(20)));
        assertEquals("0m", Uptime.format(Duration.ofMinutes(-5)));
    }
}
