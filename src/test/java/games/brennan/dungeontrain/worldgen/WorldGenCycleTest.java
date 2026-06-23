package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-math tests for the combined {@link WorldGenCycle} — the single repeating sequence
 * {@code OW → Nether → OW → End → (repeat)}. Built via the record constructor (no config),
 * so no NeoForge bootstrap.
 *
 * <p>Geometry: anchor 1000, owGap 300; nether (stageBlocks 40 → riseLen 120, mult2 5,
 * mult3 20, megaHold 60, coreFade 50, coreHold 200) → netherLen 660; end (fade 100,
 * void 40, end 200) → endLen 680; period {@code 2·300 + 660 + 680 = 1940}. Layout offset
 * from 1000: OW [0,300), Nether [300,960), OW [960,1260), End [1260,1940).</p>
 */
final class WorldGenCycleTest {

    private static final WorldGenCycle C = new WorldGenCycle(1000L, 300, 40, 5, 20, 60, 50, 200, 100, 40, 200);
    private static final int PERIOD = 1940;
    private static final double EPS = 1e-9;

    @Test
    @DisplayName("layout lengths: riseLen, netherLen, endLen, period")
    void geometry() {
        assertEquals(120, C.riseLen());
        assertEquals(660L, C.netherLen());
        assertEquals(680L, C.endLen());
        assertEquals(PERIOD, C.period());
    }

    @Test
    @DisplayName("nothing before the anchor or in the overworld gaps")
    void overworldGaps() {
        for (int wx : new int[] {999, 1000, 1299, 2000, 2259}) { // before anchor, lead OW, mid OW
            assertEquals(0.0, C.netherHeightRamp(wx), EPS, "nether height at " + wx);
            assertEquals(0.0, C.netherRamp(wx), EPS, "nether ramp at " + wx);
            assertEquals(0.0, C.endMiddleRamp(wx), EPS, "end middle at " + wx);
            assertEquals(0.0, C.endIslandRamp(wx), EPS, "end island at " + wx);
            assertEquals(1.0, C.netherMountainMultiplier(wx), EPS, "mult at " + wx); // no amplification outside
        }
    }

    @Test
    @DisplayName("the Nether segment comes first: mountain present + nether core, End silent there")
    void netherSegment() {
        // mountain rise (offset 380 → ln 80, stage 3): heightRamp > 0
        org.junit.jupiter.api.Assertions.assertTrue(C.netherHeightRamp(1380) > 0.0);
        assertEquals(1.0, C.netherRamp(1530), EPS);       // offset 530 → ln 230 → real-Nether core
        assertEquals(0.0, C.endMiddleRamp(1380), EPS);    // End is silent in the nether segment
        assertEquals(0.0, C.endIslandRamp(1530), EPS);
    }

    @Test
    @DisplayName("heightmap multiplier ramps ×1 (stage 1) → ×5 (stage 2) → ×20 (stage 3), held across the mega + core")
    void mountainMultiplier() {
        assertEquals(1.0, C.netherMountainMultiplier(1320), EPS);  // ln 20  — stage 1 (×1)
        assertEquals(3.0, C.netherMountainMultiplier(1360), EPS);  // ln 60  — stage 2 midpoint (1→5)
        assertEquals(5.0, C.netherMountainMultiplier(1380), EPS);  // ln 80  — stage 2 end (×5)
        assertEquals(12.5, C.netherMountainMultiplier(1400), EPS); // ln 100 — stage 3 midpoint (5→20)
        assertEquals(20.0, C.netherMountainMultiplier(1420), EPS); // ln 120 — stage 3 end (×20)
        assertEquals(20.0, C.netherMountainMultiplier(1450), EPS); // ln 150 — mega plateau (×20)
        assertEquals(20.0, C.netherMountainMultiplier(1600), EPS); // ln 300 — core region (held ×20)
    }

    @Test
    @DisplayName("the End segment comes after: void/island ramps active, Nether silent there")
    void endSegment() {
        assertEquals(1.0, C.endMiddleRamp(2360), EPS);    // offset 1360 → End hold
        assertEquals(1.0, C.endIslandRamp(2500), EPS);    // offset 1500 → End core
        assertEquals(0.0, C.netherHeightRamp(2360), EPS); // Nether is silent in the End segment
        assertEquals(0.0, C.netherRamp(2500), EPS);
        assertEquals(1.0, C.netherMountainMultiplier(2360), EPS);
    }

    @Test
    @DisplayName("the whole sequence repeats forever with the combined period")
    void repeats() {
        for (int off : new int[] {320, 380, 530, 1360, 1500, 100, 1000}) {
            int wx = 1000 + off;
            assertEquals(C.netherHeightRamp(wx), C.netherHeightRamp(wx + PERIOD), EPS, "nether@" + off);
            assertEquals(C.netherMountainMultiplier(wx), C.netherMountainMultiplier(wx + PERIOD), EPS, "mult@" + off);
            assertEquals(C.endMiddleRamp(wx), C.endMiddleRamp(wx + PERIOD), EPS, "end@" + off);
        }
    }

    @Test
    @DisplayName("a disabled phase collapses to zero length")
    void disabledCollapse() {
        WorldGenCycle endOnly = new WorldGenCycle(0L, 300, 0, 5, 20, 0, 0, 0, 100, 40, 200);
        assertEquals(0L, endOnly.netherLen());
        assertEquals(680L, endOnly.endLen());
        assertEquals(2L * 300 + 680, endOnly.period());
        assertEquals(0.0, endOnly.netherHeightRamp(500), EPS);
        assertEquals(1.0, endOnly.netherMountainMultiplier(500), EPS);
    }
}
