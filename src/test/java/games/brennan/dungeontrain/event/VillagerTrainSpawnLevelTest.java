package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.difficulty.ProceduralTiers;
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
    private static final int[] EXPECTED_WEIGHTS = {35, 39, 23, 2, 1};

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
        // (e.g. swapped to uniform 20%/20%/20%/20%/20% — the L4 and L5
        // buckets sit in [0%, 4%] and [-1%, 3%] at 100 000 samples, so a 20%
        // bucket would land well outside either). At 100k samples the
        // standard deviation for the 1% bucket is ~0.31pp and for the 2%
        // bucket is ~0.44pp — both leave ~4-6σ of headroom inside the ±2pp
        // band, so the test will not flake on the heavy-tailed distribution.
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

    @Test
    @DisplayName("maxLevelForTier pairs the villager cap to the mob weapon stage")
    void maxLevelForTier_pairsToWeaponStage() {
        assertEquals(1, VillagerTrainSpawnEvents.maxLevelForTier(0));   // none → 1
        assertEquals(2, VillagerTrainSpawnEvents.maxLevelForTier(1));   // wood → 2
        assertEquals(2, VillagerTrainSpawnEvents.maxLevelForTier(7));
        assertEquals(3, VillagerTrainSpawnEvents.maxLevelForTier(8));   // stone → 3
        assertEquals(3, VillagerTrainSpawnEvents.maxLevelForTier(17));
        assertEquals(4, VillagerTrainSpawnEvents.maxLevelForTier(18));  // iron → 4
        assertEquals(4, VillagerTrainSpawnEvents.maxLevelForTier(31));
        assertEquals(5, VillagerTrainSpawnEvents.maxLevelForTier(32));  // diamond → 5
        assertEquals(5, VillagerTrainSpawnEvents.maxLevelForTier(49));
        assertEquals(5, VillagerTrainSpawnEvents.maxLevelForTier(50));  // netherite clamps at 5
        assertEquals(5, VillagerTrainSpawnEvents.maxLevelForTier(100));
    }

    @Test
    @DisplayName("maxLevelForTier always equals min(5, weaponStage + 1)")
    void maxLevelForTier_matchesWeaponStageFormula() {
        // Robust to curve retuning: the contract is the pairing, not the tier edges.
        for (int tier = 0; tier <= 60; tier++) {
            int expected = Math.min(5, ProceduralTiers.dominantWeaponStage(tier) + 1);
            assertEquals(expected, VillagerTrainSpawnEvents.maxLevelForTier(tier),
                "maxLevelForTier diverged from weapon stage at tier " + tier);
        }
    }

    @Test
    @DisplayName("cappedLevel stays in [1, 5] and never exceeds the tier cap")
    void cappedLevel_inRangeAndCapped() {
        for (int tier : new int[]{0, 1, 8, 18, 32, 100}) {
            int cap = VillagerTrainSpawnEvents.maxLevelForTier(tier);
            RandomSource rng = RandomSource.create(0xBADF00DL + tier);
            for (int i = 0; i < 5_000; i++) {
                int lv = VillagerTrainSpawnEvents.cappedLevel(rng, tier);
                assertTrue(lv >= 1 && lv <= 5, "cappedLevel out of [1,5]: " + lv + " (tier " + tier + ")");
                assertTrue(lv <= cap, "cappedLevel " + lv + " exceeded cap " + cap + " (tier " + tier + ")");
            }
        }
    }

    @Test
    @DisplayName("cappedLevel forces level 1 at tier 0 (no weapons → Novice only)")
    void cappedLevel_tierZeroAlwaysOne() {
        RandomSource rng = RandomSource.create(0x5EEDL);
        for (int i = 0; i < 5_000; i++) {
            assertEquals(1, VillagerTrainSpawnEvents.cappedLevel(rng, 0));
        }
    }

    @Test
    @DisplayName("cappedLevel is uncapped at diamond tier (matches pickLevel distribution)")
    void cappedLevel_uncappedAtHighTier() {
        // At tier 32 the cap is 5, so cappedLevel == pickLevel — same distribution.
        final int samples = 100_000;
        int[] counts = new int[6];
        RandomSource rng = RandomSource.create(0xC0FFEEL);
        for (int i = 0; i < samples; i++) {
            counts[VillagerTrainSpawnEvents.cappedLevel(rng, 32)]++;
        }
        for (int lv = 1; lv <= 5; lv++) {
            double expectedPct = EXPECTED_WEIGHTS[lv - 1];
            double observedPct = 100.0 * counts[lv] / samples;
            assertTrue(Math.abs(observedPct - expectedPct) < 2.0,
                String.format("L%d: expected ~%.1f%%, observed %.2f%% (count=%d)",
                    lv, expectedPct, observedPct, counts[lv]));
        }
    }

    @Test
    @DisplayName("cappedLevel equals min(pickLevel, cap) for the same seed")
    void cappedLevel_matchesMinOfPickLevelAndCap() {
        for (int tier : new int[]{0, 1, 5, 8, 12, 18, 32}) {
            RandomSource a = RandomSource.create(99L);
            RandomSource b = RandomSource.create(99L);
            int cap = VillagerTrainSpawnEvents.maxLevelForTier(tier);
            for (int i = 0; i < 200; i++) {
                int capped = VillagerTrainSpawnEvents.cappedLevel(a, tier);
                int expected = Math.min(VillagerTrainSpawnEvents.pickLevel(b), cap);
                assertEquals(expected, capped, "mismatch at tier " + tier + " iteration " + i);
            }
        }
    }

    @Test
    @DisplayName("cappedLevel is deterministic for a given seed and tier")
    void cappedLevel_deterministicForSameSeed() {
        RandomSource a = RandomSource.create(7L);
        RandomSource b = RandomSource.create(7L);
        for (int i = 0; i < 100; i++) {
            assertEquals(
                VillagerTrainSpawnEvents.cappedLevel(a, 12),
                VillagerTrainSpawnEvents.cappedLevel(b, 12),
                "cappedLevel diverged at iteration " + i);
        }
    }
}
