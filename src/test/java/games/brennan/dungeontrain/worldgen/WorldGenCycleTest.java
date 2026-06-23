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

    private static final WorldGenCycle C = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 0);
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
    @DisplayName("isNetherCore is true only in the real-Nether core, not the netherrack crossfade or mountains")
    void netherCore() {
        org.junit.jupiter.api.Assertions.assertTrue(C.isNetherCore(1530));  // ln 230 — core start (netherRamp 1.0)
        org.junit.jupiter.api.Assertions.assertTrue(C.isNetherCore(1630));  // ln 330 — deep in the core
        org.junit.jupiter.api.Assertions.assertFalse(C.isNetherCore(1505)); // ln 205 — netherrack crossfade (~0.5)
        org.junit.jupiter.api.Assertions.assertFalse(C.isNetherCore(1380)); // ln 80  — mountain (netherRamp 0)
        org.junit.jupiter.api.Assertions.assertFalse(C.isNetherCore(2000)); // overworld gap
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
    @DisplayName("an arbitrary N-stage multiplier list ramps smoothly through each stage (1,2,4,8,15)")
    void fiveStageMultipliers() {
        // anchor 0, owGap 0 → nether starts at offset 0; stageBlocks 40, 5 stages → riseLen 200.
        WorldGenCycle d = new WorldGenCycle(0L, 0, 40, new int[] {1, 2, 4, 8, 15}, 0, 60, 50, 200, 0, 0, 0, 0);
        assertEquals(200, d.riseLen());
        assertEquals(1.0, d.netherMountainMultiplier(0), EPS);    // stage 1 start
        assertEquals(1.0, d.netherMountainMultiplier(40), EPS);   // → stage 2 start (held ×1 through stage 1)
        assertEquals(2.0, d.netherMountainMultiplier(80), EPS);   // → stage 3 start
        assertEquals(4.0, d.netherMountainMultiplier(120), EPS);  // → stage 4 start
        assertEquals(8.0, d.netherMountainMultiplier(160), EPS);  // → stage 5 start
        assertEquals(15.0, d.netherMountainMultiplier(200), EPS); // mega plateau (last value held)
        assertEquals(1.5, d.netherMountainMultiplier(60), EPS);   // stage 2 midpoint (1→2)
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
    @DisplayName("a leading beach span lengthens the rise, reads as base ×1, and reports the band entrance")
    void beachStage() {
        WorldGenCycle b = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 40, 60, 50, 200, 100, 40, 200, 0);
        assertEquals(160, b.riseLen());                                       // 40 beach + 3×40 stages
        org.junit.jupiter.api.Assertions.assertTrue(b.isNetherBeachStage(1320));  // ln 20 — inside the beach span
        assertEquals(1.0, b.netherMountainMultiplier(1320), EPS);            // beach base multiplier (feature boosts over ocean)
        org.junit.jupiter.api.Assertions.assertFalse(b.isNetherBeachStage(1360)); // ln 60 — past the beach, in the mountains
        assertEquals(1300L, b.netherBandEntranceX(1320));                    // band rise begins at the anchor + owGap
        org.junit.jupiter.api.Assertions.assertFalse(b.isNetherBeachStage(2000)); // outside the nether segment entirely
    }

    @Test
    @DisplayName("beach progress ramps 0 (seaward waterline) → 1 (inland, meeting the mountains)")
    void beachProgress() {
        WorldGenCycle b = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 40, 60, 50, 200, 100, 40, 200, 0);
        assertEquals(0.0, b.netherBeachProgress(1300), EPS);   // ln 0  — seaward entrance edge (the waterline)
        assertEquals(0.5, b.netherBeachProgress(1320), EPS);   // ln 20 — halfway up the shore
        org.junit.jupiter.api.Assertions.assertTrue(b.netherBeachProgress(1339) > 0.9); // ln 39 — almost at the mountains
    }

    @Test
    @DisplayName("edge feather: 0 at the leading + trailing gates, smoothstep to 1 over one stage, 1 across the interior")
    void mountainEdgeFeather() {
        // beach 40, stageBlocks 40 → fade 40; riseLen 160, netherLen 740; ln == worldX − 1300; leading gate ln=40.
        WorldGenCycle b = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 40, 60, 50, 200, 100, 40, 200, 0);
        assertEquals(740L, b.netherLen());
        assertEquals(0.0, b.netherMountainFeather(1340), EPS);   // ln 40  — leading gate (added height starts at 0)
        assertEquals(0.5, b.netherMountainFeather(1360), EPS);   // ln 60  — fade midpoint (smoothstep 0.5)
        assertEquals(1.0, b.netherMountainFeather(1380), EPS);   // ln 80  — fade end (full height)
        assertEquals(1.0, b.netherMountainFeather(1670), EPS);   // ln 370 — deep interior
        assertEquals(1.0, b.netherMountainFeather(2000), EPS);   // ln 700 — one stage before the trailing gate
        org.junit.jupiter.api.Assertions.assertTrue(b.netherMountainFeather(2039) < 0.05); // ln 739 — just inside trailing gate
        assertEquals(1.0, b.netherMountainFeather(2500), EPS);   // outside the nether segment → no-op
        // Non-decreasing across the whole leading fade (symmetric on the trailing side via min()).
        double prev = -1.0;
        for (int wx = 1340; wx <= 1380; wx++) {
            double f = b.netherMountainFeather(wx);
            org.junit.jupiter.api.Assertions.assertTrue(f + EPS >= prev, "feather must be non-decreasing across the fade at wx=" + wx);
            prev = f;
        }
    }

    @Test
    @DisplayName("edge feather is a no-op (1.0) when the band has length but no mountain stages")
    void featherNoStages() {
        // beach 0, stageBlocks 0 (fade 0) but a real-Nether core 200 → netherLen 200, so the column is
        // inside the band yet the fade==0 guard makes the feather a pure no-op (no div-by-zero).
        WorldGenCycle coreOnly = new WorldGenCycle(0L, 0, 0, new int[] {1}, 0, 0, 0, 200, 0, 0, 0, 0);
        assertEquals(200L, coreOnly.netherLen());
        assertEquals(1.0, coreOnly.netherMountainFeather(100), EPS); // ln 100, fade 0 → 1.0
    }

    @Test
    @DisplayName("phaseShift slides the whole cycle so the first nether band arrives earlier")
    void phaseShift() {
        // Same geometry as C but shifted 100 blocks into the cycle.
        WorldGenCycle p = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 100);
        assertEquals(0.0, C.netherHeightRamp(1260), EPS);  // unshifted (C): still overworld at cycle offset 260
        org.junit.jupiter.api.Assertions.assertTrue(p.netherHeightRamp(1260) > 0.0); // shifted: the nether band has begun
        assertEquals(p.netherHeightRamp(1260), p.netherHeightRamp(1260 + PERIOD), EPS); // shift is constant across cycles
    }

    @Test
    @DisplayName("a disabled phase collapses to zero length")
    void disabledCollapse() {
        WorldGenCycle endOnly = new WorldGenCycle(0L, 300, 0, new int[] {1, 5, 20}, 0, 0, 0, 0, 100, 40, 200, 0);
        assertEquals(0L, endOnly.netherLen());
        assertEquals(680L, endOnly.endLen());
        assertEquals(2L * 300 + 680, endOnly.period());
        assertEquals(0.0, endOnly.netherHeightRamp(500), EPS);
        assertEquals(1.0, endOnly.netherMountainMultiplier(500), EPS);
    }
}
