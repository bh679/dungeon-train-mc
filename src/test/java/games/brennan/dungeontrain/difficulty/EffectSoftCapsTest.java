package games.brennan.dungeontrain.difficulty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link EffectSoftCaps}: Resistance and Regeneration keep improving past IV but never max out. */
final class EffectSoftCapsTest {

    private static final double EPS = 0.01;
    private static final int MAX_AMPLIFIER = 255;

    @Test
    @DisplayName("levels I-IV stay vanilla; V and above are soft-capped")
    void softCapStartsAtLevelFive() {
        assertFalse(EffectSoftCaps.isSoftCapped(3));
        assertTrue(EffectSoftCaps.isSoftCapped(4));
    }

    @Test
    @DisplayName("Resistance lets through 80% / level")
    void resistanceValues() {
        assertEquals(0.20, EffectSoftCaps.resistanceDamageFraction(3), EPS);
        assertEquals(0.16, EffectSoftCaps.resistanceDamageFraction(4), EPS);
        assertEquals(0.08, EffectSoftCaps.resistanceDamageFraction(9), EPS);
        assertEquals(0.04, EffectSoftCaps.resistanceDamageFraction(19), EPS);
    }

    @Test
    @DisplayName("Regeneration climbs from 3.33 towards 10 HP/s")
    void regenValues() {
        assertEquals(3.33, EffectSoftCaps.regenHealPerSecond(3), EPS);
        assertEquals(4.67, EffectSoftCaps.regenHealPerSecond(4), EPS);
        assertEquals(7.33, EffectSoftCaps.regenHealPerSecond(9), EPS);
        assertEquals(8.67, EffectSoftCaps.regenHealPerSecond(19), EPS);
    }

    @Test
    @DisplayName("both curves improve every level and never reach their limit")
    void monotonicAndBounded() {
        for (int amp = 4; amp < MAX_AMPLIFIER; amp++) {
            double taken = EffectSoftCaps.resistanceDamageFraction(amp);
            double next = EffectSoftCaps.resistanceDamageFraction(amp + 1);
            assertTrue(next < taken && next > 0, "resistance at " + (amp + 1) + ": " + next);

            double heal = EffectSoftCaps.regenHealPerSecond(amp);
            double nextHeal = EffectSoftCaps.regenHealPerSecond(amp + 1);
            assertTrue(nextHeal > heal && nextHeal < EffectSoftCaps.REGEN_CEILING_HP_PER_SEC,
                    "regen at " + (amp + 1) + ": " + nextHeal);
        }
    }
}
