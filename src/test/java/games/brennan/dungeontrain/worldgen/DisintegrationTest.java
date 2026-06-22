package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math unit tests for the world disintegration band ({@link Disintegration}).
 * No NeoForge bootstrap — every method under test takes its parameters directly,
 * matching the {@code DifficultyProgressionTest} convention.
 */
final class DisintegrationTest {

    private static final long START_X = 1000L; // startCarriages × carriageLength
    private static final int FADE = 100;
    private static final int CORE = 60;
    private static final double EPS = 1e-9;

    // ---- voidRamp -----------------------------------------------------------

    @Test
    @DisplayName("voidRamp is 0 before the band and for backward (negative) X")
    void voidRamp_zeroBeforeBand() {
        assertEquals(0.0, Disintegration.voidRamp(999, START_X, FADE, CORE), EPS);
        assertEquals(0.0, Disintegration.voidRamp(0, START_X, FADE, CORE), EPS);
        assertEquals(0.0, Disintegration.voidRamp(-5000, START_X, FADE, CORE), EPS);
    }

    @Test
    @DisplayName("voidRamp ramps linearly 0→1 across the fade-in")
    void voidRamp_fadeIn() {
        assertEquals(0.0, Disintegration.voidRamp(1000, START_X, FADE, CORE), EPS);
        assertEquals(0.5, Disintegration.voidRamp(1050, START_X, FADE, CORE), EPS);
        assertEquals(0.99, Disintegration.voidRamp(1099, START_X, FADE, CORE), EPS);
    }

    @Test
    @DisplayName("voidRamp holds 1 across the full-void core")
    void voidRamp_core() {
        assertEquals(1.0, Disintegration.voidRamp(1100, START_X, FADE, CORE), EPS); // start of core
        assertEquals(1.0, Disintegration.voidRamp(1159, START_X, FADE, CORE), EPS); // end of core
        assertEquals(1.0, Disintegration.voidRamp(1160, START_X, FADE, CORE), EPS); // start of fade-out
    }

    @Test
    @DisplayName("voidRamp ramps linearly 1→0 across the fade-out, then 0 after the band")
    void voidRamp_fadeOutThenZero() {
        assertEquals(0.5, Disintegration.voidRamp(1210, START_X, FADE, CORE), EPS);
        assertEquals(0.01, Disintegration.voidRamp(1259, START_X, FADE, CORE), EPS);
        assertEquals(0.0, Disintegration.voidRamp(1260, START_X, FADE, CORE), EPS); // band end (exclusive)
        assertEquals(0.0, Disintegration.voidRamp(5000, START_X, FADE, CORE), EPS);
    }

    @Test
    @DisplayName("voidRamp survives fade=0 (hard edges) and core=0 (no plateau) without dividing by zero")
    void voidRamp_degenerateSpans() {
        // fade=0, core=40 → hard void band [startX, startX+40)
        assertEquals(0.0, Disintegration.voidRamp(999, START_X, 0, 40), EPS);
        assertEquals(1.0, Disintegration.voidRamp(1000, START_X, 0, 40), EPS);
        assertEquals(1.0, Disintegration.voidRamp(1039, START_X, 0, 40), EPS);
        assertEquals(0.0, Disintegration.voidRamp(1040, START_X, 0, 40), EPS);
        // core=0 → a single fade-in/out peak at startX+fade
        assertEquals(1.0, Disintegration.voidRamp(1100, START_X, FADE, 0), EPS);
    }

    // ---- removal probability + depth bias -----------------------------------

    @Test
    @DisplayName("removalProbability is 0 outside the band")
    void removal_zeroOutsideBand() {
        assertEquals(0.0, Disintegration.removalProbability(500, 60, 64, START_X, FADE, CORE), EPS);
    }

    @Test
    @DisplayName("deeper blocks erode at a higher probability than blocks at the bed")
    void removal_depthBias() {
        int bedY = 64;
        int span = (int) Disintegration.VERTICAL_SPAN;
        double atBed = Disintegration.removalProbabilityFromRamp(0.3, bedY, bedY);
        double deep = Disintegration.removalProbabilityFromRamp(0.3, bedY - span, bedY); // full depth boost
        assertEquals(0.3, atBed, EPS);                 // no boost at bed level
        assertTrue(deep > atBed, "deep block should erode more than bed-level block");
        assertEquals(0.3 * (1.0 + Disintegration.DEPTH_WEIGHT), deep, EPS);
    }

    @Test
    @DisplayName("blocks above the bed get no depth boost; probability never exceeds 1")
    void removal_aboveBedAndClamp() {
        int bedY = 64;
        int span = (int) Disintegration.VERTICAL_SPAN;
        assertEquals(0.4, Disintegration.removalProbabilityFromRamp(0.4, bedY + 20, bedY), EPS);
        assertEquals(1.0, Disintegration.removalProbabilityFromRamp(1.0, bedY - span, bedY), EPS); // clamped
    }

    // ---- band geometry helpers ----------------------------------------------

    @Test
    @DisplayName("bandStartX = startCarriages × carriageLength; bandLength = 2·fade + core")
    void bandGeometry() {
        assertEquals(2250L, Disintegration.bandStartX(250, 9));
        assertEquals(90L, Disintegration.bandStartX(10, 9)); // testing value
        assertEquals(260L, Disintegration.bandLength(FADE, CORE));
    }

    @Test
    @DisplayName("chunkInBand intersects [startX, startX+len) on X")
    void chunkInBand() {
        long len = Disintegration.bandLength(FADE, CORE); // 260 → band [1000, 1260)
        assertTrue(Disintegration.chunkInBand(992, 1007, START_X, len));   // straddles start
        assertTrue(Disintegration.chunkInBand(1100, 1115, START_X, len));  // inside
        assertTrue(Disintegration.chunkInBand(1248, 1263, START_X, len));  // straddles end
        assertFalse(Disintegration.chunkInBand(976, 991, START_X, len));   // before
        assertFalse(Disintegration.chunkInBand(1264, 1279, START_X, len)); // after
    }

    // ---- coherent noise ------------------------------------------------------

    @Test
    @DisplayName("coherentNoise stays in [0,1), is deterministic, and varies with position")
    void noise_rangeAndDeterminism() {
        long seed = 123456789L;
        double a = Disintegration.coherentNoise(seed, 10, 40, -3);
        double b = Disintegration.coherentNoise(seed, 10, 40, -3);
        assertEquals(a, b, 0.0, "same inputs must yield the same value (stable across reloads)");
        boolean varied = false;
        for (int x = 0; x < 64; x++) {
            for (int y = 30; y < 40; y++) {
                double v = Disintegration.coherentNoise(seed, x, y, 0);
                assertTrue(v >= 0.0 && v < 1.0, "noise out of [0,1): " + v);
                if (Math.abs(v - a) > 1e-6) varied = true;
            }
        }
        assertTrue(varied, "noise should vary across positions");
    }
}
