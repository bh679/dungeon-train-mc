package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-math tests for the combined {@link WorldGenCycle} — the single repeating sequence
 * {@code OW → Nether → OW → End → (repeat)}. Built via the record constructor (no config),
 * so no NeoForge bootstrap.
 *
 * <p>Geometry: anchor 1000, owGap 300; nether (fade100, mtn60, coreFade50, core200) →
 * netherLen 620; end (fade100, void40, end200) → endLen 680; period
 * {@code 2·300 + 620 + 680 = 1900}. Layout offset from 1000: OW [0,300), Nether [300,920),
 * OW [920,1220), End [1220,1900).</p>
 */
final class WorldGenCycleTest {

    private static final WorldGenCycle C = new WorldGenCycle(1000L, 300, 100, 60, 50, 200, 100, 40, 200);
    private static final int PERIOD = 1900;
    private static final double EPS = 1e-9;

    @Test
    @DisplayName("layout lengths: netherLen, endLen, period")
    void geometry() {
        assertEquals(620L, C.netherLen());
        assertEquals(680L, C.endLen());
        assertEquals(PERIOD, C.period());
    }

    @Test
    @DisplayName("nothing before the anchor or in the overworld gaps")
    void overworldGaps() {
        for (int wx : new int[] {999, 1000, 1299, 1920, 2219}) { // before anchor, lead OW, mid OW
            assertEquals(0.0, C.netherHeightRamp(wx), EPS, "nether height at " + wx);
            assertEquals(0.0, C.netherRamp(wx), EPS, "nether ramp at " + wx);
            assertEquals(0.0, C.endMiddleRamp(wx), EPS, "end middle at " + wx);
            assertEquals(0.0, C.endIslandRamp(wx), EPS, "end island at " + wx);
        }
    }

    @Test
    @DisplayName("the Nether segment comes first: full-height mountain + nether core, End silent there")
    void netherSegment() {
        assertEquals(1.0, C.netherHeightRamp(1400), EPS); // offset 400 → mountain plateau
        assertEquals(1.0, C.netherRamp(1600), EPS);       // offset 600 → real-Nether core
        assertEquals(0.0, C.endMiddleRamp(1400), EPS);    // End is silent in the nether segment
        assertEquals(0.0, C.endIslandRamp(1600), EPS);
    }

    @Test
    @DisplayName("the End segment comes after: void/island ramps active, Nether silent there")
    void endSegment() {
        assertEquals(1.0, C.endMiddleRamp(2320), EPS);    // offset 1320 → End hold
        assertEquals(1.0, C.endIslandRamp(2460), EPS);    // offset 1460 → End core
        assertEquals(0.0, C.netherHeightRamp(2320), EPS); // Nether is silent in the End segment
        assertEquals(0.0, C.netherRamp(2460), EPS);
    }

    @Test
    @DisplayName("the whole sequence repeats forever with the combined period")
    void repeats() {
        for (int off : new int[] {400, 600, 1320, 1460, 100, 1000}) {
            int wx = 1000 + off;
            assertEquals(C.netherHeightRamp(wx), C.netherHeightRamp(wx + PERIOD), EPS, "nether@" + off);
            assertEquals(C.endMiddleRamp(wx), C.endMiddleRamp(wx + PERIOD), EPS, "end@" + off);
        }
    }

    @Test
    @DisplayName("mountain top holds at normal height (stage 1) then climbs to the world top, 0 outside the segment")
    void shapedMountainHeight() {
        int bedY = 76, maxHeight = 250, baseHeight = 100, normalHold = 64, worldTop = 319;
        // before the rise reaches normal: bedY (offset 300 = nether start)
        assertEquals(76, C.netherMountainTopY(1300, bedY, maxHeight, baseHeight, normalHold, worldTop));
        // stage-1 normal-height hold (offset 340 → ln 40, inside the hold): bedY + baseHeight
        assertEquals(176, C.netherMountainTopY(1340, bedY, maxHeight, baseHeight, normalHold, worldTop));
        // interior mega-mountain (offset 600): clamped to the world top
        assertEquals(319, C.netherMountainTopY(1600, bedY, maxHeight, baseHeight, normalHold, worldTop));
        // outside the nether segment (in the End region): no mountain
        assertEquals(76, C.netherMountainTopY(2320, bedY, maxHeight, baseHeight, normalHold, worldTop));
    }

    @Test
    @DisplayName("a disabled phase collapses to zero length")
    void disabledCollapse() {
        WorldGenCycle endOnly = new WorldGenCycle(0L, 300, 0, 0, 0, 0, 100, 40, 200);
        assertEquals(0L, endOnly.netherLen());
        assertEquals(680L, endOnly.endLen());
        assertEquals(2L * 300 + 680, endOnly.period());
        assertEquals(0.0, endOnly.netherHeightRamp(500), EPS);
    }
}
