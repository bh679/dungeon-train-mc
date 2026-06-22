package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math unit tests for the disintegration band ({@link Disintegration}) — the
 * Overworld → Void → End → Void cycle that <b>repeats forever</b>, anchored to a block
 * distance from spawn. No NeoForge bootstrap.
 *
 * <p>Test geometry: {@code startX=1000} (blocks from spawn), fade {@code F=100},
 * voidHold {@code VH=40}, endHold {@code EH=200}, overworldHold {@code OW=80}. Each
 * cycle starts with the overworld phase {@code [0,80)}, then the active band
 * {@code 4F+2VH+EH=680}; full period {@code 80+680=760}.</p>
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
    @DisplayName("middleRamp is 0 before the anchor and across the overworld phase that starts each cycle")
    void middle_overworldPhase() {
        assertEquals(0.0, Disintegration.middleRamp(999, START_X, F, VH, EH, OW), EPS);  // before anchor
        assertEquals(0.0, Disintegration.middleRamp(-5000, START_X, F, VH, EH, OW), EPS);
        assertEquals(0.0, Disintegration.middleRamp(1000, START_X, F, VH, EH, OW), EPS); // d=0 overworld
        assertEquals(0.0, Disintegration.middleRamp(1079, START_X, F, VH, EH, OW), EPS); // d=79 overworld
    }

    @Test
    @DisplayName("after the overworld phase: 0→1 fade, flat 1 across void+End+void, 1→0 fade")
    void middle_band() {
        assertEquals(0.0, Disintegration.middleRamp(1080, START_X, F, VH, EH, OW), EPS); // dd=0
        assertEquals(0.5, Disintegration.middleRamp(1130, START_X, F, VH, EH, OW), EPS); // dd=50
        assertEquals(1.0, Disintegration.middleRamp(1180, START_X, F, VH, EH, OW), EPS); // dd=100 hold start
        assertEquals(1.0, Disintegration.middleRamp(1400, START_X, F, VH, EH, OW), EPS); // End core
        assertEquals(1.0, Disintegration.middleRamp(1660, START_X, F, VH, EH, OW), EPS); // dd=580 fade-out start
        assertEquals(0.5, Disintegration.middleRamp(1710, START_X, F, VH, EH, OW), EPS); // dd=630
    }

    @Test
    @DisplayName("the cycle repeats forever: ramps are periodic with period OW + 4F + 2VH + EH")
    void cycle_repeatsForever() {
        assertEquals(PERIOD, Disintegration.cyclePeriod(F, VH, EH, OW));
        for (int d : new int[] {0, 50, 90, 130, 400, 710}) {
            assertEquals(Disintegration.middleRamp((int) START_X + d, START_X, F, VH, EH, OW),
                    Disintegration.middleRamp((int) START_X + d + PERIOD, START_X, F, VH, EH, OW), EPS,
                    "middleRamp not periodic at offset " + d);
        }
        assertEquals(Disintegration.endRamp(1400, START_X, F, VH, EH, OW),
                Disintegration.endRamp(1400 + PERIOD, START_X, F, VH, EH, OW), EPS);
        assertEquals(1.0, Disintegration.endRamp(1400 + PERIOD, START_X, F, VH, EH, OW), EPS); // End core again
        assertEquals(0.0, Disintegration.middleRamp(1000 + PERIOD, START_X, F, VH, EH, OW), EPS); // overworld again
    }

    // ---- phase shift (shorter first overworld) ------------------------------

    @Test
    @DisplayName("phaseShift shortens the FIRST overworld (5k) while the recurring one stays full (10k); later phases unshifted")
    void phaseShift_firstOverworldShorter() {
        // Production-shaped geometry, anchored at spawn: OW 10000, fade 120, void 500, End 5000.
        long sx = 0L;
        int f = 120, vh = 500, eh = 5000, ow = 10000;
        int shift = 5000;                                            // = ow − firstOverworld(5000)
        int period = (int) Disintegration.cyclePeriod(f, vh, eh, ow); // 16480

        // First overworld is only 5000 blocks: the void fade begins at X≈5000, not X=10000.
        assertEquals(0.0, Disintegration.middleRamp(0, sx, shift, f, vh, eh, ow), EPS);     // spawn = overworld
        assertEquals(0.0, Disintegration.middleRamp(4999, sx, shift, f, vh, eh, ow), EPS);  // still overworld
        assertEquals(0.5, Disintegration.middleRamp(5060, sx, shift, f, vh, eh, ow), EPS);  // mid OW→void fade
        assertEquals(1.0, Disintegration.middleRamp(5120, sx, shift, f, vh, eh, ow), EPS);  // first void reached
        // With no shift the same X is still overworld — proves the shift is what shortens the first leg.
        assertEquals(0.0, Disintegration.middleRamp(5060, sx, 0, f, vh, eh, ow), EPS);

        // End islands fill across the core (endRamp == 1) through the middle of the band.
        assertEquals(1.0, Disintegration.endRamp(5740, sx, shift, f, vh, eh, ow), EPS);
        assertEquals(1.0, Disintegration.endRamp(10000, sx, shift, f, vh, eh, ow), EPS);

        // Overworld resumes at X=11480 and runs a FULL 10000 before the next void fade (~21540).
        assertEquals(0.0, Disintegration.middleRamp(11480, sx, shift, f, vh, eh, ow), EPS); // recurring OW start
        assertEquals(0.0, Disintegration.middleRamp(21479, sx, shift, f, vh, eh, ow), EPS); // still OW 9999 blocks later
        assertEquals(0.5, Disintegration.middleRamp(21540, sx, shift, f, vh, eh, ow), EPS); // next void fade, a period on

        // Ramps remain periodic under the shift.
        assertEquals(Disintegration.middleRamp(5060, sx, shift, f, vh, eh, ow),
                Disintegration.middleRamp(5060 + period, sx, shift, f, vh, eh, ow), EPS);
    }

    // ---- skyRamp (End sky/fog, lagged behind the terrain) -------------------

    @Test
    @DisplayName("skyRamp with offset 0 is identical to middleRamp across the cycle")
    void sky_offsetZero_matchesMiddle() {
        for (int d : new int[] {-50, 0, 50, 79, 80, 130, 180, 400, 660, 710, 759, 760}) {
            int x = (int) START_X + d;
            assertEquals(Disintegration.middleRamp(x, START_X, F, VH, EH, OW),
                    Disintegration.skyRamp(x, START_X, F, VH, EH, OW, 0), EPS,
                    "skyRamp(offset=0) should equal middleRamp at offset " + d);
        }
    }

    @Test
    @DisplayName("skyRamp delays the entry fade: stays 0 across the terrain crumble, reaches 1 offset+fade in")
    void sky_entry_delayed() {
        int o = 60; // active band dd = worldX - (START_X + OW) = worldX - 1080
        assertEquals(0.0, Disintegration.skyRamp(1080, START_X, F, VH, EH, OW, o), EPS); // dd=0
        assertEquals(0.0, Disintegration.skyRamp(1139, START_X, F, VH, EH, OW, o), EPS); // dd=59, still overworld sky
        assertEquals(0.0, Disintegration.skyRamp(1140, START_X, F, VH, EH, OW, o), EPS); // dd=60, fade begins
        assertEquals(0.5, Disintegration.skyRamp(1190, START_X, F, VH, EH, OW, o), EPS); // dd=110
        assertEquals(1.0, Disintegration.skyRamp(1240, START_X, F, VH, EH, OW, o), EPS); // dd=160 = o+fade, full End
        // ...while the terrain (middleRamp) was already mid-crumble at dd=0..60:
        assertTrue(Disintegration.middleRamp(1100, START_X, F, VH, EH, OW) > 0.0,
                "terrain should already be eroding while the sky is still overworld");
    }

    @Test
    @DisplayName("skyRamp advances the exit fade: returns to 0 before the terrain finishes reforming")
    void sky_exit_early() {
        int o = 60; // band=680: fall over dd [520,620), reaches 0 at dd=620
        assertEquals(1.0, Disintegration.skyRamp(1600, START_X, F, VH, EH, OW, o), EPS); // dd=520 fade-out start
        assertEquals(0.5, Disintegration.skyRamp(1650, START_X, F, VH, EH, OW, o), EPS); // dd=570
        assertEquals(0.0, Disintegration.skyRamp(1700, START_X, F, VH, EH, OW, o), EPS); // dd=620, sky back to overworld
        // ...while the terrain (middleRamp) is still > 0 there (its fade-out runs dd [580,680)):
        assertTrue(Disintegration.middleRamp(1700, START_X, F, VH, EH, OW) > 0.0,
                "sky should return to overworld while the terrain is still reforming");
    }

    @Test
    @DisplayName("skyRamp clamps a huge offset so the hold collapses but never inverts; stays in [0,1]")
    void sky_largeOffset_clamped() {
        // band=680, fade=100 → maxOffset=(680-2·100)/2=240; a triangle peaking at dd=340.
        int o = 100_000;
        assertEquals(1.0, Disintegration.skyRamp(1420, START_X, F, VH, EH, OW, o), EPS); // dd=340 peak
        assertEquals(0.5, Disintegration.skyRamp(1370, START_X, F, VH, EH, OW, o), EPS); // dd=290 rising
        assertEquals(0.5, Disintegration.skyRamp(1470, START_X, F, VH, EH, OW, o), EPS); // dd=390 falling
        for (int dd = 0; dd <= 680; dd++) {
            double s = Disintegration.skyRamp(1080 + dd, START_X, F, VH, EH, OW, o);
            assertTrue(s >= 0.0 && s <= 1.0, "skyRamp out of [0,1] at dd=" + dd + ": " + s);
        }
    }

    @Test
    @DisplayName("skyRamp survives fade=0 (hard edges) without dividing by zero")
    void sky_degenerate_fade() {
        assertEquals(0.0, Disintegration.skyRamp(1000, START_X, 0, VH, EH, OW, 30), EPS);       // overworld phase
        assertEquals(1.0, Disintegration.skyRamp(1000 + OW, START_X, 0, VH, EH, OW, 0), EPS);   // band start, offset 0
    }

    // ---- endRamp (End-island fill) ------------------------------------------

    @Test
    @DisplayName("endRamp is 0 through overworld/void, 0→1 into the End, holds 1, 1→0 back to void")
    void end_trapezoid() {
        assertEquals(0.0, Disintegration.endRamp(1050, START_X, F, VH, EH, OW), EPS); // overworld phase
        assertEquals(0.0, Disintegration.endRamp(1200, START_X, F, VH, EH, OW), EPS); // dd=120 void hold V1
        assertEquals(0.5, Disintegration.endRamp(1270, START_X, F, VH, EH, OW), EPS); // dd=190 void→End
        assertEquals(1.0, Disintegration.endRamp(1320, START_X, F, VH, EH, OW), EPS); // dd=240 End core
        assertEquals(1.0, Disintegration.endRamp(1520, START_X, F, VH, EH, OW), EPS); // dd=440 End→void
        assertEquals(0.5, Disintegration.endRamp(1570, START_X, F, VH, EH, OW), EPS); // dd=490
        assertEquals(0.0, Disintegration.endRamp(1640, START_X, F, VH, EH, OW), EPS); // dd=560 void hold V2
    }

    @Test
    @DisplayName("void holds: middleRamp = 1 (full erosion) while endRamp = 0 (no End terrain)")
    void void_holds_are_pure_void() {
        assertEquals(1.0, Disintegration.middleRamp(1200, START_X, F, VH, EH, OW), EPS); // dd=120
        assertEquals(0.0, Disintegration.endRamp(1200, START_X, F, VH, EH, OW), EPS);
    }

    @Test
    @DisplayName("ramps survive fade=0 (hard edges) without dividing by zero")
    void degenerate_fade() {
        assertEquals(0.0, Disintegration.middleRamp(999, START_X, 0, VH, EH, OW), EPS);          // before anchor
        assertEquals(0.0, Disintegration.middleRamp(1000, START_X, 0, VH, EH, OW), EPS);         // overworld phase
        assertEquals(1.0, Disintegration.middleRamp(1000 + OW, START_X, 0, VH, EH, OW), EPS);    // band start, M=1
        assertEquals(1.0, Disintegration.endRamp(1000 + OW + VH, START_X, 0, VH, EH, OW), EPS);  // End core
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
    @DisplayName("removalProbability via worldX: 0 in the overworld phase, full erosion in the void/End middle")
    void removal_byWorldX() {
        assertEquals(0.0, Disintegration.removalProbability(1050, 64, 64, START_X, F, VH, EH, OW), EPS); // overworld
        assertEquals(1.0, Disintegration.removalProbability(1400, 64, 64, START_X, F, VH, EH, OW), EPS); // M=1 at bed
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
    @DisplayName("bandLength = 4·fade + 2·voidHold + endHold; cyclePeriod adds the overworld hold")
    void bandGeometry() {
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
