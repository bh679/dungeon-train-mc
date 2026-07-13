package games.brennan.dungeontrain.worldgen.feature;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Correctness + dedup guarantee for the Phase-2 Nether-core corner cache
 * ({@link NetherTransitionFeature#cornerProfile}). The cache is what cuts the confirmed
 * Nether-crossing bottleneck (12–33 ms/chunk of real-Nether router sampling), so it must be
 * <b>byte-identical</b> to the previous per-column re-sampling and must evaluate each distinct corner
 * exactly once. Driven with a deterministic counting stub {@link DensityFunction} — no NeoForge
 * bootstrap needed (only {@code compute(SinglePointContext)} is exercised).
 */
final class NetherTransitionFeatureCornerCacheTest {

    /** Deterministic stub Nether router: {@code compute} is a pure function of (x,y,z), counting calls. */
    private static final class CountingDensity implements DensityFunction {
        final AtomicInteger calls = new AtomicInteger();

        static double f(int x, int y, int z) {
            return Math.sin(x * 0.11 + y * 0.07 + z * 0.13);
        }

        @Override public double compute(FunctionContext c) {
            calls.incrementAndGet();
            return f(c.blockX(), c.blockY(), c.blockZ());
        }
        @Override public void fillArray(double[] values, ContextProvider provider) { throw new UnsupportedOperationException(); }
        @Override public DensityFunction mapAll(Visitor visitor) { return this; }
        @Override public double minValue() { return -1.0; }
        @Override public double maxValue() { return 1.0; }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { throw new UnsupportedOperationException(); }
    }

    @Test
    @DisplayName("cornerProfile returns values byte-identical to a direct compute at every row")
    void cachedProfileIsByteIdentical() {
        CountingDensity df = new CountingDensity();
        Map<Long, double[]> cache = new HashMap<>();
        int cellRowBase = 3, rows = 16, cellH = 8;

        for (int x : new int[] {0, 4, 8, -4, -8, 100, 2048}) {
            for (int z : new int[] {0, 4, -4, 64, -64, 512}) {
                double[] profile = NetherTransitionFeature.cornerProfile(cache, df, x, z, cellRowBase, rows, cellH);
                assertEquals(rows, profile.length);
                for (int r = 0; r < rows; r++) {
                    int by = (cellRowBase + r) * cellH;
                    assertEquals(CountingDensity.f(x, by, z), profile[r], 0.0,
                            "corner (" + x + "," + z + ") row " + r);
                }
                // A second lookup for the same corner returns the identical cached array reference.
                assertEquals(profile, NetherTransitionFeature.cornerProfile(cache, df, x, z, cellRowBase, rows, cellH));
            }
        }
    }

    @Test
    @DisplayName("each distinct corner is evaluated exactly once; repeated references are pure cache hits")
    void eachDistinctCornerComputedOnce() {
        CountingDensity df = new CountingDensity();
        Map<Long, double[]> cache = new HashMap<>();
        int cellRowBase = 0, rows = 10, cellH = 8;
        int[][] corners = {{0, 0}, {4, 0}, {0, 4}, {4, 4}, {100, -64}};

        // First reference of each distinct corner → rows compute() calls apiece.
        for (int[] c : corners) {
            NetherTransitionFeature.cornerProfile(cache, df, c[0], c[1], cellRowBase, rows, cellH);
        }
        assertEquals(corners.length * rows, df.calls.get(), "one profile (rows computes) per distinct corner");

        // 20 more passes — as adjacent block-columns re-reference the same corners — add NO computes.
        for (int rep = 0; rep < 20; rep++) {
            for (int[] c : corners) {
                NetherTransitionFeature.cornerProfile(cache, df, c[0], c[1], cellRowBase, rows, cellH);
            }
        }
        assertEquals(corners.length * rows, df.calls.get(), "cache hits must add zero compute() calls");
    }
}
