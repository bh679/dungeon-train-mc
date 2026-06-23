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
    @DisplayName("smooth01 is in [0,1], deterministic, varies, and is gentler than the ridged height01")
    void smoothNoise() {
        long seed = 0xBEEF1234L;
        assertEquals(MountainNoise.smooth01(seed, 10, -3), MountainNoise.smooth01(seed, 10, -3), 0.0);
        boolean varied = false;
        double prev = MountainNoise.smooth01(seed, 0, 0);
        double smoothStep = 0.0, ridgedStep = 0.0;
        for (int x = 1; x < 400; x++) {
            double s = MountainNoise.smooth01(seed, x, 0);
            assertTrue(s >= 0.0 && s <= 1.0, "smooth out of [0,1]: " + s);
            if (Math.abs(s - prev) > 1e-6) varied = true;
            smoothStep += Math.abs(s - MountainNoise.smooth01(seed, x - 1, 0));
            ridgedStep += Math.abs(MountainNoise.height01(seed, x, 0) - MountainNoise.height01(seed, x - 1, 0));
            prev = s;
        }
        assertTrue(varied, "smooth noise should vary across positions");
        // Non-ridged noise has smaller block-to-block changes than the sharpened ridged variant.
        assertTrue(smoothStep < ridgedStep, "smooth01 should be gentler than height01 (" + smoothStep + " vs " + ridgedStep + ")");
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
