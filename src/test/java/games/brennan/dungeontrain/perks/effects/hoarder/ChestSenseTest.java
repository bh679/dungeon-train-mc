package games.brennan.dungeontrain.perks.effects.hoarder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Chest Sense's curves; the scan needs a level (and Sable), so it is covered in-game. */
final class ChestSenseTest {

    @Test
    @DisplayName("nothing at rank 0")
    void offAtZero() {
        assertEquals(0.0, ChestSense.range(0));
        assertEquals(0, ChestSense.glowSeconds(0));
    }

    @Test
    @DisplayName("range 4 + 2 per point, glow 5 + 1 s per point, uncapped")
    void curves() {
        assertEquals(6.0, ChestSense.range(1));
        assertEquals(24.0, ChestSense.range(10));
        assertEquals(104.0, ChestSense.range(50));
        assertEquals(6, ChestSense.glowSeconds(1));
        assertEquals(15, ChestSense.glowSeconds(10));
    }

    @Test
    @DisplayName("one pulse every 5 minutes")
    void cooldown() {
        assertEquals(5 * 60 * 20, ChestSense.cooldownTicks());
    }
}
