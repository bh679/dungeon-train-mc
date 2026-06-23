package games.brennan.dungeontrain.worldgen.feature;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-math tests for the synthetic mountain heightmap {@link MountainNoise}. */
final class MountainNoiseTest {

    @Test
    @DisplayName("height is in [0,1], deterministic, and varies across positions (so terrain is never flat)")
    void rangeDeterminismVariation() {
        long seed = 123456789L;
        assertEquals(MountainNoise.height01(seed, 10, -3), MountainNoise.height01(seed, 10, -3), 0.0);
        double first = MountainNoise.height01(seed, 0, 0);
        boolean varied = false;
        double lo = 1.0, hi = 0.0;
        for (int x = 0; x < 400; x += 7) {
            for (int z = 0; z < 400; z += 11) {
                double h = MountainNoise.height01(seed, x, z);
                assertTrue(h >= 0.0 && h <= 1.0, "out of [0,1]: " + h);
                lo = Math.min(lo, h);
                hi = Math.max(hi, h);
                if (Math.abs(h - first) > 1e-6) varied = true;
            }
        }
        assertTrue(varied, "noise should vary across positions");
        assertTrue(hi - lo > 0.3, "noise should span a meaningful relief range, got " + (hi - lo));
    }

    @Test
    @DisplayName("different seeds give different mountains")
    void seedDependent() {
        boolean differs = false;
        for (int x = 0; x < 200 && !differs; x += 13) {
            if (Math.abs(MountainNoise.height01(1L, x, 0) - MountainNoise.height01(2L, x, 0)) > 1e-6) differs = true;
        }
        assertTrue(differs, "distinct seeds should produce distinct heightmaps");
    }
}
