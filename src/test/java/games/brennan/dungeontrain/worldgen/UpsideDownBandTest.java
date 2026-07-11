package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-geometry tests for the upside-down band's bedrock-roof lid
 * ({@link UpsideDownBand#bedrockRoofY}) and the entry lead-in reveal window
 * ({@link UpsideDownBand#revealYExtent}). The full block-mirror handler is integration-tested
 * in-game; here we just lock down the Y arithmetic and its clamps.
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
}
