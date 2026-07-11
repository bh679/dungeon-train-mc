package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-geometry tests for the upside-down band's bedrock-roof lid
 * ({@link UpsideDownBand#bedrockRoofY}). The full block-mirror handler is integration-tested
 * in-game; here we just lock down the Y arithmetic and its clamp.
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
}
