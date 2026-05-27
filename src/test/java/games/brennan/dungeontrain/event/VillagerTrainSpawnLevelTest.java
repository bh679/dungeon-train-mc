package games.brennan.dungeontrain.event;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link VillagerTrainSpawnEvents#pickLevel}. Pure-logic
 * coverage of the weighted villager-level roll: range guarantee, distribution
 * within tolerance of the static weight table, and determinism on a fixed
 * seed. No Forge / NeoForge bootstrap — uses {@link RandomSource#create(long)}
 * directly, mirroring the {@code CarriageWeightsTest} pattern.
 */
final class VillagerTrainSpawnLevelTest {

    /**
     * Expected weights matching {@code VillagerTrainSpawnEvents.LEVEL_WEIGHTS}.
     * Duplicated here intentionally: a code-side typo in the source array
     * would silently pass if we shared the constant, so the test asserts
     * against the *contract* (a fixed table) rather than the implementation.
     */
    private static final int[] EXPECTED_WEIGHTS = {30, 35, 20, 10, 5};

    @Test
    @DisplayName("pickLevel returns a value in [1, 5] across many samples")
    void pickLevel_inRange() {
        RandomSource rng = RandomSource.create(0xDEADBEEFL);
        for (int i = 0; i < 10_000; i++) {
            int lv = VillagerTrainSpawnEvents.pickLevel(rng);
            assertTrue(lv >= 1 && lv <= 5,
                "pickLevel returned out-of-range value: " + lv);
        }
    }

    @Test
    @DisplayName("pickLevel distribution matches LEVEL_WEIGHTS within tolerance")
    void pickLevel_distributionMatchesWeights() {
        final int samples = 100_000;
        int[] counts = new int[6]; // index 1..5 in use
        RandomSource rng = RandomSource.create(0xC0FFEEL);
        for (int i = 0; i < samples; i++) {
            counts[VillagerTrainSpawnEvents.pickLevel(rng)]++;
        }
        // ±2 percentage-points tolerance per bucket — loose enough to absorb
        // single-seed luck, tight enough to fail if the weight table is wrong
        // (e.g. swapped to uniform 20%/20%/20%/20%/20% — the L5 bucket alone
        // would fall outside 3-7% of 100 000 samples).
        for (int lv = 1; lv <= 5; lv++) {
            double expectedPct = EXPECTED_WEIGHTS[lv - 1];
            double observedPct = 100.0 * counts[lv] / samples;
            assertTrue(Math.abs(observedPct - expectedPct) < 2.0,
                String.format("L%d: expected ~%.1f%%, observed %.2f%% (count=%d)",
                    lv, expectedPct, observedPct, counts[lv]));
        }
    }

    @Test
    @DisplayName("pickLevel is deterministic for a given seed")
    void pickLevel_deterministicForSameSeed() {
        RandomSource a = RandomSource.create(42L);
        RandomSource b = RandomSource.create(42L);
        for (int i = 0; i < 100; i++) {
            assertEquals(
                VillagerTrainSpawnEvents.pickLevel(a),
                VillagerTrainSpawnEvents.pickLevel(b),
                "pickLevel diverged at iteration " + i + " for same seed");
        }
    }
}
