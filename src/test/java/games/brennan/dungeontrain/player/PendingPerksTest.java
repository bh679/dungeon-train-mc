package games.brennan.dungeontrain.player;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Perk unlocks carried across Board anew: held for one player, one profile, once. */
class PendingPerksTest {

    private static final UUID ME = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @AfterEach
    void clear() {
        PendingPerks.clear();
    }

    @Test
    @DisplayName("held only for the player it was captured for")
    void presentForOwnerOnly() {
        assertFalse(PendingPerks.isPresentFor(ME));
        PendingPerks.set(ME, Map.of("health", 2), "normal");
        assertTrue(PendingPerks.isPresentFor(ME));
        assertFalse(PendingPerks.isPresentFor(UUID.randomUUID()));
        assertEquals(Map.of("health", 2), PendingPerks.carried());
    }

    @Test
    @DisplayName("lands only on the difficulty profile it was captured on")
    void partition() {
        PendingPerks.set(ME, Map.of("health", 2), "normal");
        assertTrue(PendingPerks.matches("normal"));
        assertFalse(PendingPerks.matches("hard"));
    }

    @Test
    @DisplayName("clear empties the holder")
    void clears() {
        PendingPerks.set(ME, Map.of("health", 2), "normal");
        PendingPerks.clear();
        assertFalse(PendingPerks.isPresentFor(ME));
        assertTrue(PendingPerks.carried().isEmpty());
    }

    @Test
    @DisplayName("merging keeps the higher level of each perk and never lowers one")
    void merged() {
        Map<String, Integer> out = PendingPerks.merged(Map.of("health", 3, "armour", 1), Map.of("health", 2, "speed", 4));
        assertEquals(Map.of("health", 3, "armour", 1, "speed", 4), out);
    }
}
