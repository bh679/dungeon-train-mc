package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rate limiter on menu-activity reports. Trivial arithmetic with one trap: seeding
 * "never sent" as {@link Integer#MIN_VALUE} overflows the subtraction and wedges the limiter
 * shut, which silently stopped every menu-mouse report from reaching the server.
 */
class ClientActivityReporterTest {

    private static final int COOLDOWN = 20;

    @Test
    @DisplayName("a report is due once the cooldown has elapsed, and not before")
    void cooldownBoundary() {
        assertFalse(ClientActivityReporter.due(19, 0, COOLDOWN));
        assertTrue(ClientActivityReporter.due(20, 0, COOLDOWN));
        assertTrue(ClientActivityReporter.due(1_000, 500, COOLDOWN));
    }

    @Test
    @DisplayName("the never-sent seed makes the first report due immediately")
    void freshSeedIsDue() {
        // What the class actually seeds with: -COOLDOWN, at the very first tick.
        assertTrue(ClientActivityReporter.due(1, -COOLDOWN, COOLDOWN));
        assertTrue(ClientActivityReporter.due(0, -COOLDOWN, COOLDOWN));
    }

    @Test
    @DisplayName("MIN_VALUE as the seed overflows and reports nothing — the bug this pins")
    void minValueSeedOverflows() {
        assertFalse(ClientActivityReporter.due(5, Integer.MIN_VALUE, COOLDOWN));
    }
}
