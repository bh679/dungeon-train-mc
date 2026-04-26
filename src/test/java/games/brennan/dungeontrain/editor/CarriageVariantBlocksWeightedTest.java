package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the v3-schema weighted picker
 * ({@link CarriageVariantBlocks#pickIndexFromWeights}). Sister-test to
 * {@link CarriageVariantBlocksTest} which covers the uniform v1/v2 picker.
 *
 * <p>These tests operate on raw {@code int[]} weight arrays so they don't
 * need a Forge/MC bootstrap to construct real
 * {@link net.minecraft.world.level.block.state.BlockState} instances. The
 * full {@link CarriageVariantBlocks#resolve} path (lock short-circuit +
 * weighted pick over a {@code List<VariantState>}) is covered by the
 * integration harness on Project #16.</p>
 */
final class CarriageVariantBlocksWeightedTest {

    @Test
    @DisplayName("weighted: same inputs always yield the same index")
    void weighted_isDeterministic() {
        BlockPos pos = new BlockPos(1, 2, 3);
        int[] weights = { 1, 3, 2 };
        int first = CarriageVariantBlocks.pickIndexFromWeights(pos, 12345L, 7, weights);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, CarriageVariantBlocks.pickIndexFromWeights(pos, 12345L, 7, weights),
                "call " + i);
        }
    }

    @Test
    @DisplayName("weighted: result is always in [0, weights.length)")
    void weighted_resultInRange() {
        BlockPos pos = new BlockPos(0, 0, 0);
        int[] weights = { 1, 1, 1, 1 };
        for (int idx = 0; idx < 64; idx++) {
            int r = CarriageVariantBlocks.pickIndexFromWeights(pos, 1L, idx, weights);
            assertTrue(r >= 0 && r < weights.length,
                "out of range: idx=" + idx + " result=" + r);
        }
    }

    @Test
    @DisplayName("weighted: empty array throws")
    void weighted_emptyThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> CarriageVariantBlocks.pickIndexFromWeights(new BlockPos(0, 0, 0), 1L, 0, new int[0]));
    }

    @Test
    @DisplayName("weighted: non-positive weights are clamped to 1 (no NPE / no skipped buckets)")
    void weighted_nonPositiveClamped() {
        BlockPos pos = new BlockPos(0, 0, 0);
        int[] weights = { 0, -5, 1 };
        // Should not throw and should pick a valid index across many seeds.
        for (int idx = 0; idx < 32; idx++) {
            int r = CarriageVariantBlocks.pickIndexFromWeights(pos, idx, 0, weights);
            assertTrue(r >= 0 && r < 3);
        }
    }

    @Test
    @DisplayName("weighted: equal weights distribute roughly uniformly across many seeds")
    void weighted_uniformDistribution() {
        // Same as the v1/v2 path — equal weights should match the uniform
        // distribution. We sample over many carriage indices because the
        // determinism contract fixes the result for a given seed.
        int[] weights = { 1, 1, 1 };
        int[] counts = new int[3];
        int total = 9000;
        for (int i = 0; i < total; i++) {
            int r = CarriageVariantBlocks.pickIndexFromWeights(new BlockPos(0, 0, 0), 0xDEADL, i, weights);
            counts[r]++;
        }
        // Expect each bucket within ±20% of total/3 (= 3000). Picker uses
        // java.util.Random, which is uniform enough for this to hold.
        int target = total / 3;
        int margin = target / 5;
        for (int i = 0; i < 3; i++) {
            assertTrue(Math.abs(counts[i] - target) < margin,
                "bucket " + i + " count " + counts[i] + " is too far from " + target);
        }
    }

    @Test
    @DisplayName("weighted: skewed weights bias the distribution toward heavier buckets")
    void weighted_skewedDistribution() {
        // Weight of 5 vs 1 vs 1 → bucket 0 should land ~5/7 ≈ 71% of the time.
        int[] weights = { 5, 1, 1 };
        int[] counts = new int[3];
        int total = 7000;
        for (int i = 0; i < total; i++) {
            int r = CarriageVariantBlocks.pickIndexFromWeights(new BlockPos(0, 0, 0), 0xCAFEL, i, weights);
            counts[r]++;
        }
        // Bucket 0 expected ~5000, buckets 1+2 ~1000 each. Allow ±20%.
        assertTrue(counts[0] > 4000 && counts[0] < 6000,
            "heavy bucket 0 count off: " + counts[0]);
        assertTrue(counts[1] > 600 && counts[1] < 1400,
            "light bucket 1 count off: " + counts[1]);
        assertTrue(counts[2] > 600 && counts[2] < 1400,
            "light bucket 2 count off: " + counts[2]);
    }

    @Test
    @DisplayName("weighted: equal weights produce same draw as uniform pickIndex (back-compat)")
    void weighted_matchesUniformWhenAllEqual() {
        // The schema-v3 migration must not change behaviour for v2 sidecars
        // that load with all weights=1. The seed mix is identical, so
        // equal-weights weighted picker should match the legacy uniform path.
        int[] equalWeights = { 1, 1, 1, 1 };
        for (int idx = 0; idx < 32; idx++) {
            int weighted = CarriageVariantBlocks.pickIndexFromWeights(
                new BlockPos(2, 4, 6), 0xBEEFL, idx, equalWeights);
            int uniform = CarriageVariantBlocks.pickIndex(
                new BlockPos(2, 4, 6), 0xBEEFL, idx, 4);
            assertEquals(uniform, weighted,
                "back-compat broken at carriageIndex=" + idx);
        }
    }

    @Test
    @DisplayName("weighted: changing weights changes the draw distribution (no aliasing)")
    void weighted_weightsAffectDraw() {
        // Sanity: a fresh weight schedule isn't accidentally ignored.
        Map<Integer, Integer> a = histogram(new int[] { 1, 1, 1 }, 0x1L, 1000);
        Map<Integer, Integer> b = histogram(new int[] { 9, 1, 1 }, 0x1L, 1000);
        // Bucket 0 should be much heavier in b than in a.
        assertTrue(b.getOrDefault(0, 0) > a.getOrDefault(0, 0) + 200,
            "bucket 0 didn't swing with the new weight: a=" + a + " b=" + b);
    }

    private static Map<Integer, Integer> histogram(int[] weights, long seed, int trials) {
        Map<Integer, Integer> hist = new HashMap<>();
        for (int i = 0; i < trials; i++) {
            int r = CarriageVariantBlocks.pickIndexFromWeights(new BlockPos(0, 0, 0), seed, i, weights);
            hist.merge(r, 1, Integer::sum);
        }
        return hist;
    }
}
