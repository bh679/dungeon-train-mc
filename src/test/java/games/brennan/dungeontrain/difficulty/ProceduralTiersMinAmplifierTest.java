package games.brennan.dungeontrain.difficulty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProceduralTiers#minAmplifier(int, int)}: a buff's floor stays at level I until
 * level 250, becomes II there, and climbs one step every 100 levels, never above the cap.
 */
final class ProceduralTiersMinAmplifierTest {

    private static final int HIGH_CAP = 100;

    @Test
    @DisplayName("the floor stays at level I below level 250")
    void levelOneBelowStart() {
        assertEquals(0, ProceduralTiers.minAmplifier(0, HIGH_CAP));
        assertEquals(0, ProceduralTiers.minAmplifier(249, HIGH_CAP));
    }

    @Test
    @DisplayName("the floor is II at 250 and steps up every 100 levels")
    void stepsEveryHundred() {
        assertEquals(1, ProceduralTiers.minAmplifier(250, HIGH_CAP));
        assertEquals(1, ProceduralTiers.minAmplifier(349, HIGH_CAP));
        assertEquals(2, ProceduralTiers.minAmplifier(350, HIGH_CAP));
        assertEquals(3, ProceduralTiers.minAmplifier(450, HIGH_CAP));
    }

    @Test
    @DisplayName("the floor never exceeds the cap, and level-less effects stay at I")
    void neverAboveCap() {
        assertEquals(2, ProceduralTiers.minAmplifier(1000, 2));
        assertEquals(0, ProceduralTiers.minAmplifier(1000, 0));
    }

    @Test
    @DisplayName("every effect spec has floor <= cap at any level")
    void specsAreConsistent() {
        for (int level = 1; level <= 2000; level += 7) {
            List<DifficultyTier.EffectSpec> specs = ProceduralTiers.effectsFor(level);
            for (DifficultyTier.EffectSpec spec : specs) {
                assertTrue(spec.minAmplifier() >= 0 && spec.minAmplifier() <= spec.amplifier(),
                        spec.id() + " at level " + level + ": " + spec.minAmplifier() + " > " + spec.amplifier());
            }
        }
    }
}
