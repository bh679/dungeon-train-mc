package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the {@link OceanBand} per-chunk island/open-water classification ({@link OceanBand#classify}).
 * The world-facing entry points ({@code kindOf}/{@code isInBand}/{@code vetoSpread}) need a {@code ServerLevel}
 * and are covered by in-game tests; here we test the seed-stable noise directly.
 */
final class OceanBandTest {

    private static final long SEED = 0x0FEEDFACEBEEF01L;

    @Test
    @DisplayName("classify is deterministic in (seed, chunkX, chunkZ)")
    void deterministic() {
        for (int i = 0; i < 50; i++) {
            int cx = i * 7 - 100, cz = i * 3 - 40;
            OceanBand.Kind a = OceanBand.classify(SEED, cx, cz, 0.2);
            OceanBand.Kind b = OceanBand.classify(SEED, cx, cz, 0.2);
            assertEquals(a, b, "same inputs must classify identically at (" + cx + "," + cz + ")");
        }
    }

    @Test
    @DisplayName("island fraction tracks the islandDensity knob")
    void distributionTracksKnob() {
        int n = 0, islands = 0;
        for (int cx = -80; cx < 80; cx++) {
            for (int cz = -80; cz < 80; cz++) {
                n++;
                if (OceanBand.classify(SEED, cx, cz, 0.08) == OceanBand.Kind.ISLAND) islands++;
            }
        }
        double frac = (double) islands / n;
        assertTrue(Math.abs(frac - 0.08) < 0.02, "island fraction " + frac + " should be ≈0.08 (sparse)");
    }

    @Test
    @DisplayName("islandDensity 0 → all open ocean; 1 → all island")
    void densityExtremes() {
        for (int cx = 0; cx < 40; cx++) {
            assertEquals(OceanBand.Kind.OCEAN, OceanBand.classify(SEED, cx, cx, 0.0));
            assertEquals(OceanBand.Kind.ISLAND, OceanBand.classify(SEED, cx, cx, 1.0));
        }
    }

    @Test
    @DisplayName("isIslandColumn maps a block position to its chunk and honours a zero density")
    void islandColumn() {
        // Density 0 is always open water regardless of position.
        assertFalse(OceanBand.isIslandColumn(SEED, 137, -42, 0.0));
        // Otherwise it agrees with classify on the containing chunk.
        int blockX = 137, blockZ = -42;
        boolean viaColumn = OceanBand.isIslandColumn(SEED, blockX, blockZ, 0.5);
        boolean viaChunk = OceanBand.classify(SEED, blockX >> 4, blockZ >> 4, 0.5) == OceanBand.Kind.ISLAND;
        assertEquals(viaChunk, viaColumn);
    }
}
