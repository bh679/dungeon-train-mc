package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-geometry tests for the upside-down band's bedrock-roof lid
 * ({@link UpsideDownBand#bedrockRoofY}), the entry lead-in reveal window
 * ({@link UpsideDownBand#revealYExtent}), and the exit-crossfade noise-skip predicates
 * ({@link UpsideDownBand#exitMirrorKeepsAll} / {@link UpsideDownBand#exitMirrorDropsAll} /
 * {@link UpsideDownBand#exitOverworldKeepsAll}). The full block-mirror handler is integration-tested
 * in-game; here we just lock down the arithmetic, its clamps, and the output-identity / depth-safety
 * guarantees of the skip.
 */
final class UpsideDownBandTest {

    @Test
    @DisplayName("roof sits one block above the highest reflected-ceiling block (source minY+1)")
    void roofSitsFlushOnReflectedCeiling() {
        // Default overworld: minY=32, maxY=320, mirror = trainY(78) + offset(0).
        // Highest reflected-ceiling block comes from source minY+1=33 → 2*78 - 33 = 123,
        // so the lid (mirror of the old minY floor) is one above = 124.
        int mirror = 78, ceilingGap = 0, minY = 32, maxY = 320;
        int roofY = UpsideDownBand.bedrockRoofY(mirror, ceilingGap, minY, maxY);
        assertEquals(124, roofY);
        int highestCeiling = 2 * mirror + ceilingGap - (minY + 1);
        assertEquals(highestCeiling + 1, roofY, "lid must cap the ceiling with no gap");
    }

    @Test
    @DisplayName("ceilingGap raises the roof by the same amount")
    void ceilingGapRaisesRoof() {
        assertEquals(134, UpsideDownBand.bedrockRoofY(78, 10, 32, 320));
    }

    @Test
    @DisplayName("a lower world floor (min_y=-64) pushes the roof up with it")
    void lowerFloorRaisesRoof() {
        // overworld_y-64 variant: min_y=-64, height=384 → maxY=320.
        assertEquals(220, UpsideDownBand.bedrockRoofY(78, 0, -64, 320));
    }

    @Test
    @DisplayName("an extreme mirror plane clamps the roof to the build ceiling (maxY-1)")
    void roofClampedToBuildTop() {
        int maxY = 320;
        int roofY = UpsideDownBand.bedrockRoofY(400, 0, 32, maxY);
        assertEquals(maxY - 1, roofY);
        assertTrue(roofY < maxY, "roof must stay inside the build range");
    }

    @Test
    @DisplayName("reveal window: 0 at reveal 0, grows monotonically, reaches the full mirror at reveal 1, clamps out of range")
    void revealYExtentGrows() {
        // Default overworld geometry: mirror 78, gaps 0, minY 32, maxY 320 → roofY 124.
        // upMax = roofY-(mirror+ceilingGap) = 46; downMax = (mirror-floorGap)-(minY+1) = 45; maxExtent = 46.
        int mirror = 78, ceilingGap = 0, floorGap = 0, minY = 32, maxY = 320;
        int roofY = UpsideDownBand.bedrockRoofY(mirror, ceilingGap, minY, maxY);

        assertEquals(0, UpsideDownBand.revealYExtent(0.0, mirror, ceilingGap, floorGap, roofY, minY));
        assertEquals(46, UpsideDownBand.revealYExtent(1.0, mirror, ceilingGap, floorGap, roofY, minY));
        assertEquals(23, UpsideDownBand.revealYExtent(0.5, mirror, ceilingGap, floorGap, roofY, minY));

        // Monotonic non-decreasing across the ramp.
        int prev = -1;
        for (int i = 0; i <= 20; i++) {
            int e = UpsideDownBand.revealYExtent(i / 20.0, mirror, ceilingGap, floorGap, roofY, minY);
            assertTrue(e >= prev, "extent must be non-decreasing at reveal " + (i / 20.0));
            prev = e;
        }

        // Out-of-range reveal clamps to [0,1].
        assertEquals(0, UpsideDownBand.revealYExtent(-0.5, mirror, ceilingGap, floorGap, roofY, minY));
        assertEquals(46, UpsideDownBand.revealYExtent(2.0, mirror, ceilingGap, floorGap, roofY, minY));
    }

    @Test
    @DisplayName("bedrock roof is reached only once the window climbs to roofY — not before")
    void roofReachedAtWindowTop() {
        int mirror = 78, ceilingGap = 0, floorGap = 0, minY = 32, maxY = 320;
        int roofY = UpsideDownBand.bedrockRoofY(mirror, ceilingGap, minY, maxY); // 124

        // At reveal 1 the ceiling has grown all the way up to the lid.
        int full = UpsideDownBand.revealYExtent(1.0, mirror, ceilingGap, floorGap, roofY, minY);
        assertTrue(mirror + ceilingGap + full >= roofY, "window must reach roofY at reveal 1");

        // Just short of full reveal, the window has NOT reached the lid — no bedrock yet.
        int near = UpsideDownBand.revealYExtent(0.9, mirror, ceilingGap, floorGap, roofY, minY);
        assertTrue(mirror + ceilingGap + near < roofY, "window must not reach roofY before the reveal completes");
    }

    // ---- exit-crossfade noise-skip predicates -------------------------------

    @Test
    @DisplayName("eps=0: mirror keeps-all only at disperse>=1.0, drops-all only at disperse<=0.0, and neither fires over the real (0,1] ramp except the l=0 edge")
    void exitMirrorStrictThresholdsAreOutputIdentical() {
        double eps = 0.0;
        assertTrue(UpsideDownBand.exitMirrorKeepsAll(1.0, eps));
        assertFalse(UpsideDownBand.exitMirrorKeepsAll(0.999, eps));
        assertTrue(UpsideDownBand.exitMirrorDropsAll(0.0, eps));
        assertFalse(UpsideDownBand.exitMirrorDropsAll(0.001, eps));

        // Over the real disperse ramp (len-l)/len ∈ (0,1]: keeps-all only at the l=0 edge, drops-all never.
        int len = 2000;
        for (int l = 0; l < len; l++) {
            double disperse = (double) (len - l) / len;
            assertEquals(l == 0, UpsideDownBand.exitMirrorKeepsAll(disperse, eps),
                    "keeps-all should fire only at l=0, at l=" + l);
            assertFalse(UpsideDownBand.exitMirrorDropsAll(disperse, eps),
                    "drops-all never fires at eps=0 over (0,1], at l=" + l);
        }
    }

    @Test
    @DisplayName("eps=0: overworld keeps-all only at reveal>=1.0 — never over the real [0,1) ramp")
    void exitOverworldStrictThresholdIsOutputIdentical() {
        double eps = 0.0;
        assertTrue(UpsideDownBand.exitOverworldKeepsAll(1.0, eps));
        assertFalse(UpsideDownBand.exitOverworldKeepsAll(0.999, eps));

        int len = 2000;
        for (int l = 0; l < len; l++) {
            double reveal = (double) l / len;             // ∈ [0,1)
            assertFalse(UpsideDownBand.exitOverworldKeepsAll(reveal, eps),
                    "keeps-all must never fire at eps=0 for reveal<1, at l=" + l);
        }
    }

    @Test
    @DisplayName("eps widens the disperse keep/drop bands symmetrically")
    void exitMirrorEpsilonWidens() {
        double eps = 0.05;
        assertTrue(UpsideDownBand.exitMirrorKeepsAll(0.95, eps));
        assertTrue(UpsideDownBand.exitMirrorKeepsAll(0.96, eps));
        assertFalse(UpsideDownBand.exitMirrorKeepsAll(0.949, eps));
        assertTrue(UpsideDownBand.exitMirrorDropsAll(0.05, eps));
        assertTrue(UpsideDownBand.exitMirrorDropsAll(0.04, eps));
        assertFalse(UpsideDownBand.exitMirrorDropsAll(0.051, eps));
    }

    @Test
    @DisplayName("disperse keep-all and drop-all bands never overlap while eps < 0.5 (config max 0.49)")
    void exitMirrorBandsDisjoint() {
        double eps = 0.49;   // config max
        for (int i = 0; i <= 100; i++) {
            double disperse = i / 100.0;
            assertFalse(UpsideDownBand.exitMirrorKeepsAll(disperse, eps)
                            && UpsideDownBand.exitMirrorDropsAll(disperse, eps),
                    "keep and drop must be disjoint at disperse=" + disperse);
        }
    }

    @Test
    @DisplayName("overworld keep-all epsilon accounts for the depth boost — skipping keeps the deepest-block removal chance within eps")
    void exitOverworldEpsilonIsDepthSafe() {
        double eps = 0.10;
        // Deepest block gets the full depth boost ×(1+DEPTH_WEIGHT) when depth reaches VERTICAL_SPAN.
        int bedY = 100;
        int deepY = (int) (bedY - Disintegration.VERTICAL_SPAN);   // depth == VERTICAL_SPAN → full boost

        // reveal=0.96 → (1-0.96)·(1+1)=0.08 ≤ eps → safe to skip: deep pRemove stays within eps.
        assertTrue(UpsideDownBand.exitOverworldKeepsAll(0.96, eps));
        double pRemoveDeepSafe = Disintegration.removalProbabilityFromRamp(1.0 - 0.96, deepY, bedY);
        assertTrue(pRemoveDeepSafe <= eps + 1e-9,
                "when we skip, the deepest-block removal chance must stay within eps (was " + pRemoveDeepSafe + ")");

        // reveal=0.94 → (0.06)·2=0.12 > eps → must NOT skip: the depth boost would exceed eps.
        assertFalse(UpsideDownBand.exitOverworldKeepsAll(0.94, eps));
        double pRemoveDeepUnsafe = Disintegration.removalProbabilityFromRamp(1.0 - 0.94, deepY, bedY);
        assertTrue(pRemoveDeepUnsafe > eps,
                "the low-reveal side we refuse to skip really would exceed eps at depth (was " + pRemoveDeepUnsafe + ")");
    }

    // ---- ceiling-height cap (STRETCH) ---------------------------------------

    @Test
    @DisplayName("ceiling cap: 0 leaves roofY unchanged; a finite cap drops the lid flush onto the slab; never raises the roof")
    void cappedRoofYDropsLidOntoCap() {
        int mirror = 78, ceilingGap = 0, minY = 32, maxY = 320;
        int roofY = UpsideDownBand.bedrockRoofY(mirror, ceilingGap, minY, maxY); // 124
        assertEquals(roofY, UpsideDownBand.cappedRoofY(roofY, mirror, ceilingGap, 0));   // 0 = off
        assertEquals(95, UpsideDownBand.cappedRoofY(roofY, mirror, ceilingGap, 16));     // 78 + 0 + 16 + 1
        assertEquals(roofY, UpsideDownBand.cappedRoofY(roofY, mirror, ceilingGap, 512)); // taller than uncapped → no raise
        // ceilingGap shifts the cap up with the ceiling start
        assertEquals(78 + 10 + 8 + 1, UpsideDownBand.cappedRoofY(roofY, 78, 10, 8));
    }
}
