package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math unit tests for the disintegration band ({@link Disintegration}) — the
 * Overworld → Void → End → Void → Overworld cycle that <b>repeats forever</b>. No
 * NeoForge bootstrap.
 *
 * <p>Test geometry: {@code startX=1000}, fade {@code F=100}, voidHold {@code VH=40},
 * endHold {@code EH=200}, overworldHold {@code OW=80}. Active band {@code 4F+2VH+EH=680};
 * full cycle period {@code 680+80=760}. Within a cycle: void holds at offset [100,140)
 * and [540,580); End core at [240,440); overworld stretch at [680,760).</p>
 */
final class DisintegrationTest {

    private static final long START_X = 1000L;
    private static final int F = 100;   // fade
    private static final int VH = 40;   // void hold
    private static final int EH = 200;  // end hold
    private static final int OW = 80;   // overworld hold
    private static final int PERIOD = 760;
    private static final double EPS = 1e-9;

    // ---- middleRamp (erosion + End sky/fog) ---------------------------------

    @Test
    @DisplayName("middleRamp is 0 before the band and for backward (negative) X")
    void middle_zeroBeforeBand() {
        assertEquals(0.0, Disintegration.middleRamp(999, START_X, F, VH, EH, OW), EPS);
        assertEquals(0.0, Disintegration.middleRamp(0, START_X, F, VH, EH, OW), EPS);
        assertEquals(0.0, Disintegration.middleRamp(-5000, START_X, F, VH, EH, OW), EPS);
    }

    @Test
    @DisplayName("middleRamp ramps 0→1, holds 1 across the void+End+void middle, 1→0, then 0 in the overworld stretch")
    void middle_trapezoidAndOverworld() {
        assertEquals(0.0, Disintegration.middleRamp(1000, START_X, F, VH, EH, OW), EPS); // d=0
        assertEquals(0.5, Disintegration.middleRamp(1050, START_X, F, VH, EH, OW), EPS); // d=50
        assertEquals(1.0, Disintegration.middleRamp(1100, START_X, F, VH, EH, OW), EPS); // d=100 hold start
        assertEquals(1.0, Disintegration.middleRamp(1300, START_X, F, VH, EH, OW), EPS); // mid (End core)
        assertEquals(1.0, Disintegration.middleRamp(1580, START_X, F, VH, EH, OW), EPS); // d=580 fade-out start
        assertEquals(0.5, Disintegration.middleRamp(1630, START_X, F, VH, EH, OW), EPS); // d=630
        assertEquals(0.0, Disintegration.middleRamp(1680, START_X, F, VH, EH, OW), EPS); // d=680 overworld stretch
        assertEquals(0.0, Disintegration.middleRamp(1730, START_X, F, VH, EH, OW), EPS); // d=730 overworld
    }

    @Test
    @DisplayName("the cycle repeats forever: ramps are periodic with period 4F+2VH+EH+OW")
    void cycle_repeatsForever() {
        assertEquals(PERIOD, Disintegration.cyclePeriod(F, VH, EH, OW));
        // middleRamp repeats every period.
        for (int d : new int[] {0, 50, 100, 300, 630, 700}) {
            assertEquals(Disintegration.middleRamp((int) START_X + d, START_X, F, VH, EH, OW),
                    Disintegration.middleRamp((int) START_X + d + PERIOD, START_X, F, VH, EH, OW), EPS,
                    "middleRamp not periodic at offset " + d);
        }
        // endRamp repeats every period.
        assertEquals(Disintegration.endRamp(1240, START_X, F, VH, EH, OW),
                Disintegration.endRamp(1240 + PERIOD, START_X, F, VH, EH, OW), EPS);
        // Second cycle's End core is solid again.
        assertEquals(1.0, Disintegration.endRamp(1240 + PERIOD, START_X, F, VH, EH, OW), EPS);
        assertEquals(1.0, Disintegration.middleRamp(1300 + PERIOD, START_X, F, VH, EH, OW), EPS);
    }

    // ---- endRamp (End-island fill) ------------------------------------------

    @Test
    @DisplayName("endRamp is 0 through void/overworld, 0→1 into the End, holds 1, 1→0 back to void")
    void end_trapezoid() {
        assertEquals(0.0, Disintegration.endRamp(1100, START_X, F, VH, EH, OW), EPS); // d=100 (void hold V1)
        assertEquals(0.0, Disintegration.endRamp(1140, START_X, F, VH, EH, OW), EPS); // d=140 void→End start
        assertEquals(0.5, Disintegration.endRamp(1190, START_X, F, VH, EH, OW), EPS); // d=190
        assertEquals(1.0, Disintegration.endRamp(1240, START_X, F, VH, EH, OW), EPS); // d=240 End core
        assertEquals(1.0, Disintegration.endRamp(1440, START_X, F, VH, EH, OW), EPS); // d=440 End→void start
        assertEquals(0.5, Disintegration.endRamp(1490, START_X, F, VH, EH, OW), EPS); // d=490
        assertEquals(0.0, Disintegration.endRamp(1540, START_X, F, VH, EH, OW), EPS); // d=540 (void hold V2)
        assertEquals(0.0, Disintegration.endRamp(1700, START_X, F, VH, EH, OW), EPS); // d=700 overworld stretch
    }

    @Test
    @DisplayName("void holds: middleRamp = 1 (full erosion) while endRamp = 0 (no End terrain)")
    void void_holds_are_pure_void() {
        assertEquals(1.0, Disintegration.middleRamp(1120, START_X, F, VH, EH, OW), EPS);
        assertEquals(0.0, Disintegration.endRamp(1120, START_X, F, VH, EH, OW), EPS);
    }

    @Test
    @DisplayName("ramps survive fade=0 (hard edges) without dividing by zero")
    void degenerate_fade() {
        assertEquals(0.0, Disintegration.middleRamp(999, START_X, 0, VH, EH, OW), EPS);
        assertEquals(1.0, Disintegration.middleRamp(1000, START_X, 0, VH, EH, OW), EPS);
        long band = Disintegration.bandLength(0, VH, EH); // 280
        assertEquals(0.0, Disintegration.middleRamp((int) (START_X + band), START_X, 0, VH, EH, OW), EPS); // overworld
        assertEquals(1.0, Disintegration.endRamp((int) (START_X + VH), START_X, 0, VH, EH, OW), EPS); // End core
    }

    // ---- removal probability (depth bias) -----------------------------------

    @Test
    @DisplayName("deeper blocks erode at a higher probability than blocks at the bed")
    void removal_depthBias() {
        int bedY = 64;
        int span = (int) Disintegration.VERTICAL_SPAN;
        double atBed = Disintegration.removalProbabilityFromRamp(0.3, bedY, bedY);
        double deep = Disintegration.removalProbabilityFromRamp(0.3, bedY - span, bedY);
        assertEquals(0.3, atBed, EPS);
        assertTrue(deep > atBed, "deep block should erode more than bed-level block");
        assertEquals(0.3 * (1.0 + Disintegration.DEPTH_WEIGHT), deep, EPS);
    }

    @Test
    @DisplayName("removalProbability via worldX: 0 before the band, full erosion in the void/End middle")
    void removal_byWorldX() {
        assertEquals(0.0, Disintegration.removalProbability(500, 64, 64, START_X, F, VH, EH, OW), EPS);
        assertEquals(1.0, Disintegration.removalProbability(1300, 64, 64, START_X, F, VH, EH, OW), EPS); // M=1 at bed
    }

    // ---- End-island density threshold (band fade for real End terrain) ------

    @Test
    @DisplayName("island density threshold is the core value in the End core and rises toward the void edges")
    void islandDensityThreshold_fade() {
        assertEquals(Disintegration.ISLAND_CORE_DENSITY, Disintegration.islandDensityThreshold(1.0), EPS);
        assertEquals(Disintegration.ISLAND_EDGE_DENSITY, Disintegration.islandDensityThreshold(0.0), EPS);
        assertEquals((Disintegration.ISLAND_CORE_DENSITY + Disintegration.ISLAND_EDGE_DENSITY) / 2.0,
                Disintegration.islandDensityThreshold(0.5), EPS);
        assertTrue(Disintegration.islandDensityThreshold(0.25) > Disintegration.islandDensityThreshold(0.75));
    }

    // ---- band geometry helpers ----------------------------------------------

    @Test
    @DisplayName("bandStartX = startCarriages × carriageLength; bandLength = 4·fade + 2·voidHold + endHold")
    void bandGeometry() {
        assertEquals(2250L, Disintegration.bandStartX(250, 9));
        assertEquals(90L, Disintegration.bandStartX(10, 9));
        assertEquals(680L, Disintegration.bandLength(F, VH, EH));
        assertEquals(760L, Disintegration.cyclePeriod(F, VH, EH, OW));
    }

    // ---- noise ---------------------------------------------------------------

    @Test
    @DisplayName("erosion noise stays in [0,1), is deterministic, and varies with position")
    void noise_rangeAndDeterminism() {
        long seed = 123456789L;
        assertEquals(Disintegration.coherentNoise(seed, 10, 40, -3),
                Disintegration.coherentNoise(seed, 10, 40, -3), 0.0);
        boolean varied = false;
        double e0 = Disintegration.coherentNoise(seed, 0, 30, 0);
        for (int x = 0; x < 64; x++) {
            for (int y = 30; y < 40; y++) {
                double e = Disintegration.coherentNoise(seed, x, y, 0);
                assertTrue(e >= 0.0 && e < 1.0, "erosion noise out of [0,1): " + e);
                if (Math.abs(e - e0) > 1e-6) varied = true;
            }
        }
        assertTrue(varied, "erosion noise should vary across positions");
    }
}
