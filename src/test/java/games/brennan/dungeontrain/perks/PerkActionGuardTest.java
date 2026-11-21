package games.brennan.dungeontrain.perks;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerkActionGuardTest {

    @Test
    void secondActionInsideTheWindowIsDropped() {
        UUID p = UUID.randomUUID();
        assertTrue(PerkActionGuard.allow(p, "health", 1000));
        assertFalse(PerkActionGuard.allow(p, "health", 1000 + PerkActionGuard.WINDOW_MS - 1));
        assertTrue(PerkActionGuard.allow(p, "speed", 1001), "another perk is not blocked");
        assertTrue(PerkActionGuard.allow(p, "health", 1000 + PerkActionGuard.WINDOW_MS));
        PerkActionGuard.forget(p);
    }
}
