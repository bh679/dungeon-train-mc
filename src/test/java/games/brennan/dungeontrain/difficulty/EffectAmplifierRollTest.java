package games.brennan.dungeontrain.difficulty;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link DifficultyApplier#rollAmplifier(int, RandomSource)}: a difficulty
 * effect's amplifier is a cap, and each mob rolls uniformly from 0 up to it.
 */
final class EffectAmplifierRollTest {

    private static final int ROLLS = 4000;

    @Test
    @DisplayName("a cap of 0 always applies level I")
    void zeroCapIsLevelOne() {
        RandomSource rng = RandomSource.create(1L);
        for (int i = 0; i < ROLLS; i++) {
            assertEquals(0, DifficultyApplier.rollAmplifier(0, rng));
        }
    }

    @Test
    @DisplayName("a negative cap clamps to level I")
    void negativeCapClamps() {
        assertEquals(0, DifficultyApplier.rollAmplifier(-3, RandomSource.create(2L)));
    }

    @Test
    @DisplayName("a cap of 3 reaches every level from I to IV and never above")
    void spreadsDownwardOnly() {
        int cap = 3;
        int[] seen = new int[cap + 1];
        RandomSource rng = RandomSource.create(3L);
        for (int i = 0; i < ROLLS; i++) {
            int amp = DifficultyApplier.rollAmplifier(cap, rng);
            assertTrue(amp >= 0 && amp <= cap, "amplifier out of range: " + amp);
            seen[amp]++;
        }
        for (int amp = 0; amp <= cap; amp++) {
            // Uniform: each level expects ROLLS / 4 = 1000; allow a wide margin.
            assertTrue(seen[amp] > ROLLS / (cap + 1) / 2, "level " + amp + " too rare: " + seen[amp]);
        }
    }
}
