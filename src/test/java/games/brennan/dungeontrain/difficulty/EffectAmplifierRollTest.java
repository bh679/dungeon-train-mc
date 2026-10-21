package games.brennan.dungeontrain.difficulty;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link DifficultyApplier#rollAmplifier(int, int, RandomSource)}: a
 * difficulty effect has a floor and a cap, and each mob rolls uniformly between them.
 */
final class EffectAmplifierRollTest {

    private static final int ROLLS = 4000;

    @Test
    @DisplayName("a cap of 0 always applies level I")
    void zeroCapIsLevelOne() {
        RandomSource rng = RandomSource.create(1L);
        for (int i = 0; i < ROLLS; i++) {
            assertEquals(0, DifficultyApplier.rollAmplifier(0, 0, rng));
        }
    }

    @Test
    @DisplayName("a negative cap clamps to level I")
    void negativeCapClamps() {
        assertEquals(0, DifficultyApplier.rollAmplifier(0, -3, RandomSource.create(2L)));
    }

    @Test
    @DisplayName("a cap of 3 reaches every level from I to IV and never above")
    void spreadsDownwardOnly() {
        int cap = 3;
        int[] seen = new int[cap + 1];
        RandomSource rng = RandomSource.create(3L);
        for (int i = 0; i < ROLLS; i++) {
            int amp = DifficultyApplier.rollAmplifier(0, cap, rng);
            assertTrue(amp >= 0 && amp <= cap, "amplifier out of range: " + amp);
            seen[amp]++;
        }
        for (int amp = 0; amp <= cap; amp++) {
            // Uniform: each level expects ROLLS / 4 = 1000; allow a wide margin.
            assertTrue(seen[amp] > ROLLS / (cap + 1) / 2, "level " + amp + " too rare: " + seen[amp]);
        }
    }

    @Test
    @DisplayName("a floor of 2 and cap of 6 reaches every level from III to VII and nothing else")
    void staysWithinFloorAndCap() {
        int min = 2;
        int cap = 6;
        int[] seen = new int[cap + 1];
        RandomSource rng = RandomSource.create(4L);
        for (int i = 0; i < ROLLS; i++) {
            int amp = DifficultyApplier.rollAmplifier(min, cap, rng);
            assertTrue(amp >= min && amp <= cap, "amplifier out of range: " + amp);
            seen[amp]++;
        }
        for (int amp = min; amp <= cap; amp++) {
            assertTrue(seen[amp] > ROLLS / (cap - min + 1) / 2, "level " + amp + " too rare: " + seen[amp]);
        }
    }

    @Test
    @DisplayName("a floor equal to the cap applies the cap and draws no randomness")
    void singleValueRangeDrawsNothing() {
        RandomSource rng = RandomSource.create(5L);
        RandomSource untouched = RandomSource.create(5L);
        assertEquals(3, DifficultyApplier.rollAmplifier(3, 3, rng));
        assertEquals(untouched.nextLong(), rng.nextLong());
    }

    @Test
    @DisplayName("a floor above the cap clamps to the cap")
    void floorAboveCapClamps() {
        assertEquals(2, DifficultyApplier.rollAmplifier(5, 2, RandomSource.create(6L)));
    }
}
