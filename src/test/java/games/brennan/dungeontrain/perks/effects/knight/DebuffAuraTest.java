package games.brennan.dungeontrain.perks.effects.knight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Debuff Aura's curves; the ticking needs a level, so it is covered in-game. */
final class DebuffAuraTest {

    @Test
    @DisplayName("nothing at rank 0")
    void offAtZero() {
        assertEquals(0.0, DebuffAura.range(0));
        assertEquals(0, DebuffAura.weaknessLevel(0));
        assertEquals(0, DebuffAura.slownessLevel(0));
        assertEquals(0.0, DebuffAura.cooldownSeconds(0));
    }

    @Test
    @DisplayName("range grows a block a point from 7")
    void range() {
        assertEquals(7.0, DebuffAura.range(1));
        assertEquals(16.0, DebuffAura.range(10));
    }

    @Test
    @DisplayName("Weakness I to 4 points, II from 5, III from 10")
    void weakness() {
        assertEquals(1, DebuffAura.weaknessLevel(1));
        assertEquals(1, DebuffAura.weaknessLevel(4));
        assertEquals(2, DebuffAura.weaknessLevel(5));
        assertEquals(3, DebuffAura.weaknessLevel(10));
    }

    @Test
    @DisplayName("Slowness joins at 2 points, II from 7")
    void slowness() {
        assertEquals(0, DebuffAura.slownessLevel(1));
        assertEquals(1, DebuffAura.slownessLevel(2));
        assertEquals(1, DebuffAura.slownessLevel(6));
        assertEquals(2, DebuffAura.slownessLevel(7));
    }

    @Test
    @DisplayName("cooldown starts at 30 s and keeps shrinking without reaching 0")
    void cooldown() {
        assertEquals(30.0, DebuffAura.cooldownSeconds(1), 1e-9);
        assertEquals(27.0, DebuffAura.cooldownSeconds(2), 1e-9);
        assertEquals(11.62, DebuffAura.cooldownSeconds(10), 0.01);
        for (int r = 1; r < 60; r++) {
            assertTrue(DebuffAura.cooldownSeconds(r + 1) < DebuffAura.cooldownSeconds(r));
            assertTrue(DebuffAura.cooldownSeconds(r + 1) > 0);
        }
    }

    @Test
    @DisplayName("each pulse's effect outlasts the one-second gap to the next")
    void effectOutlastsPulse() {
        assertTrue(DebuffAuraEvents.EFFECT_TICKS > 20);
    }
}
