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

    private static final WorldGenCycle C = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 0, 0, 0, 0);
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
    @DisplayName("upside-down band: disabled is byte-identical; enabled flows directly out of End, ramps 0→1→0, then a trailing exit gap")
    void upsideDownBand() {
        // Disabled (C has udFade=udHold=udExit=0): zero length, period unchanged.
        assertEquals(0L, C.upsideDownLen());
        assertEquals(PERIOD, C.period());
        org.junit.jupiter.api.Assertions.assertFalse(C.isInUpsideDownBand(3240));

        // Same base as C but with the band enabled (udFade 50, udHold 200 → udLen 300) and a
        // 150-block trailing exit gap before the cycle repeats.
        WorldGenCycle u = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 50, 200, 150, 0);
        assertEquals(300L, u.upsideDownLen());
        // period grows by udLen (300) + the trailing exit gap (150): 1940 + 300 + 150 = 2390.
        assertEquals(2390L, u.period());

        // No gap between End and Upside-down: band world-X = anchor + udStart = [1000+1940, 1000+2240) = [2940, 3240).
        org.junit.jupiter.api.Assertions.assertFalse(u.isInUpsideDownBand(2939)); // still the End band
        assertEquals(0.0, u.upsideDownRamp(2939), EPS);
        assertEquals(0.0, u.upsideDownRamp(2940), EPS);   // band entry (fade start), immediately after End
        assertEquals(0.5, u.upsideDownRamp(2965), EPS);   // half-way up the 50-block fade
        assertEquals(1.0, u.upsideDownRamp(3090), EPS);   // core, held at 1
        assertEquals(0.5, u.upsideDownRamp(3215), EPS);   // half-way down the fade-out
        org.junit.jupiter.api.Assertions.assertTrue(u.isInUpsideDownBand(2940));
        org.junit.jupiter.api.Assertions.assertTrue(u.isInUpsideDownBand(3239));
        org.junit.jupiter.api.Assertions.assertFalse(u.isInUpsideDownBand(3240)); // one past the band — exit gap begins
        assertEquals(0.0, u.upsideDownRamp(3240), EPS);

        // Trailing exit gap [3240, 3390) is plain overworld — not in the band, ramp 0 — before the
        // cycle wraps at 3390 back into the next period's leading owGap.
        org.junit.jupiter.api.Assertions.assertFalse(u.isInUpsideDownBand(3389));
        assertEquals(0.0, u.upsideDownRamp(3389), EPS);

        // (upsideDownEntryLead() below covers the new entry lead-in zone in detail.)

        // Disjoint from the nether/End segments (no UD ramp inside them).
        assertEquals(0.0, u.upsideDownRamp(1530), EPS);   // nether core
        assertEquals(0.0, u.upsideDownRamp(2500), EPS);   // End segment
    }

    @Test
    @DisplayName("exit crossfade: adds its length to the period, sits right after the band, OW-reveal 0→1 / mirror-disperse 1→0, and stretches the trailing sky fade across it")
    void upsideDownExitFade() {
        // C (no band): no exit crossfade either.
        assertEquals(0L, C.udExitFadeLen());
        org.junit.jupiter.api.Assertions.assertFalse(C.isInUpsideDownExitFade(3240));

        // Same base as upsideDownBand() (band udLen 300, exit gap 150) plus an 800-block exit crossfade
        // inserted between the band and the trailing exit gap (16-arg form; udExitFade = 800).
        WorldGenCycle e = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 50, 200, 150, 800, 0);
        assertEquals(300L, e.upsideDownLen());
        assertEquals(800L, e.udExitFadeLen());
        // period = 1940 + udLen 300 + exitFade 800 + exit gap 150 = 3190.
        assertEquals(3190L, e.period());

        // Band [2940,3240); exit crossfade immediately after it [3240,4040); exit gap [4040,4190).
        org.junit.jupiter.api.Assertions.assertFalse(e.isInUpsideDownExitFade(3239)); // still the band
        org.junit.jupiter.api.Assertions.assertTrue(e.isInUpsideDownExitFade(3240));  // starts at band end
        org.junit.jupiter.api.Assertions.assertTrue(e.isInUpsideDownExitFade(4039));
        org.junit.jupiter.api.Assertions.assertFalse(e.isInUpsideDownExitFade(4040)); // exit gap begins
        org.junit.jupiter.api.Assertions.assertFalse(e.isInUpsideDownBand(3240));      // disjoint from the band

        // Overworld-reveal ramps 0→1 across the zone; mirror-disperse is its complement 1→0.
        assertEquals(0.0, e.upsideDownExitOwRevealRamp(3240), EPS);
        assertEquals(0.5, e.upsideDownExitOwRevealRamp(3640), EPS);            // 400/800
        assertEquals(799.0 / 800.0, e.upsideDownExitOwRevealRamp(4039), EPS);
        assertEquals(1.0, e.upsideDownExitMirrorDisperseRamp(3240), EPS);      // full mirror, continuous with the band
        assertEquals(0.5, e.upsideDownExitMirrorDisperseRamp(3640), EPS);
        assertEquals(1.0 / 800.0, e.upsideDownExitMirrorDisperseRamp(4039), EPS);
        assertEquals(0.0, e.upsideDownExitOwRevealRamp(3239), EPS);            // 0 outside the zone
        assertEquals(0.0, e.upsideDownExitMirrorDisperseRamp(4040), EPS);

        // Atmosphere: with an exit crossfade present the band's trailing edge HOLDS at 1 (no in-band
        // fade-out); the sky instead fades 1→0 across the whole exit crossfade.
        assertEquals(1.0, e.upsideDownRamp(3215), EPS);   // was 0.5 without the exit fade — now held at 1
        assertEquals(1.0, e.upsideDownRamp(3240), EPS);   // exit start — continuous with the held band
        assertEquals(0.5, e.upsideDownRamp(3640), EPS);   // half-way down the exit sky fade
        assertEquals(0.0, e.upsideDownRamp(4040), EPS);   // exit gap — sky back to normal
    }

    @Test
    @DisplayName("entry lead-in: disabled/zero-void is zero-length; clamped by eVoid; reveal ramps 0→1 up to udStart, disjoint from the true band")
    void upsideDownEntryLead() {
        // Disabled (C: udFade=udHold=0): zero-length lead-in, never true anywhere.
        assertEquals(0L, C.udEntryLeadLen());
        org.junit.jupiter.api.Assertions.assertFalse(C.isInUpsideDownEntryLead(2920));
        assertEquals(0.0, C.upsideDownEntryRevealRamp(2920), EPS);

        // u: eVoid 40, udFade 50 → lead clamped to eVoid (40), NOT the full udFade (50). udStart
        // offset 1940 (world-X 2940, per upsideDownBand() above), so leadStart offset 1900 (world-X 2900).
        WorldGenCycle u = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 50, 200, 150, 0);
        assertEquals(40L, u.udEntryLeadLen());                                     // clamped by eVoid, not udFade

        org.junit.jupiter.api.Assertions.assertFalse(u.isInUpsideDownEntryLead(2899)); // still End band proper
        assertEquals(0.0, u.upsideDownEntryRevealRamp(2899), EPS);

        org.junit.jupiter.api.Assertions.assertTrue(u.isInUpsideDownEntryLead(2900));  // lead-in starts
        assertEquals(0.0, u.upsideDownEntryRevealRamp(2900), EPS);
        assertEquals(0.5, u.upsideDownEntryRevealRamp(2920), EPS);                 // halfway (20/40)
        assertEquals(39.0 / 40.0, u.upsideDownEntryRevealRamp(2939), EPS);         // one block before udStart
        org.junit.jupiter.api.Assertions.assertTrue(u.isInUpsideDownEntryLead(2939));

        // At udStart the true band takes over — lead-in and band are disjoint, not overlapping.
        org.junit.jupiter.api.Assertions.assertFalse(u.isInUpsideDownEntryLead(2940));
        assertEquals(0.0, u.upsideDownEntryRevealRamp(2940), EPS);
        org.junit.jupiter.api.Assertions.assertTrue(u.isInUpsideDownBand(2940));

        // Unclamped case: eVoid (200) comfortably exceeds udFade (50) → lead-in is the full udFade.
        WorldGenCycle w = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 200, 200, 50, 200, 150, 0);
        assertEquals(50L, w.udEntryLeadLen());

        // Combined predicate (used by render-flip / water-freeze / corridor): true across BOTH the
        // lead-in [2900,2940) and the band [2940,3240), false in the void before leadStart, the trailing
        // exit gap, and plain overworld.
        org.junit.jupiter.api.Assertions.assertFalse(u.isInUpsideDownBandOrEntryLead(2899)); // End void before lead-in
        org.junit.jupiter.api.Assertions.assertTrue(u.isInUpsideDownBandOrEntryLead(2900));  // lead-in start
        org.junit.jupiter.api.Assertions.assertTrue(u.isInUpsideDownBandOrEntryLead(2939));  // lead-in end
        org.junit.jupiter.api.Assertions.assertTrue(u.isInUpsideDownBandOrEntryLead(2940));  // band start
        org.junit.jupiter.api.Assertions.assertTrue(u.isInUpsideDownBandOrEntryLead(3239));  // band end
        org.junit.jupiter.api.Assertions.assertFalse(u.isInUpsideDownBandOrEntryLead(3240)); // trailing exit gap
        org.junit.jupiter.api.Assertions.assertFalse(u.isInUpsideDownBandOrEntryLead(1530)); // nether core
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
        WorldGenCycle d = new WorldGenCycle(0L, 0, 40, new int[] {1, 2, 4, 8, 15}, 0, 60, 50, 200, 0, 0, 0, 0, 0, 0, 0);
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
        WorldGenCycle b = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 40, 60, 50, 200, 100, 40, 200, 0, 0, 0, 0);
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
        WorldGenCycle b = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 40, 60, 50, 200, 100, 40, 200, 0, 0, 0, 0);
        assertEquals(0.0, b.netherBeachProgress(1300), EPS);   // ln 0  — seaward entrance edge (the waterline)
        assertEquals(0.5, b.netherBeachProgress(1320), EPS);   // ln 20 — halfway up the shore
        org.junit.jupiter.api.Assertions.assertTrue(b.netherBeachProgress(1339) > 0.9); // ln 39 — almost at the mountains
    }

    @Test
    @DisplayName("edge feather: 0 at the leading + trailing gates, smoothstep to 1 over one stage, 1 across the interior")
    void mountainEdgeFeather() {
        // beach 40, stageBlocks 40 → fade 40; riseLen 160, netherLen 740; ln == worldX − 1300; leading gate ln=40.
        WorldGenCycle b = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 40, 60, 50, 200, 100, 40, 200, 0, 0, 0, 0);
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
        WorldGenCycle coreOnly = new WorldGenCycle(0L, 0, 0, new int[] {1}, 0, 0, 0, 200, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(200L, coreOnly.netherLen());
        assertEquals(1.0, coreOnly.netherMountainFeather(100), EPS); // ln 100, fade 0 → 1.0
    }

    @Test
    @DisplayName("phaseShift slides the whole cycle so the first nether band arrives earlier")
    void phaseShift() {
        // Same geometry as C but shifted 100 blocks into the cycle.
        WorldGenCycle p = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 0, 0, 0, 100);
        assertEquals(0.0, C.netherHeightRamp(1260), EPS);  // unshifted (C): still overworld at cycle offset 260
        org.junit.jupiter.api.Assertions.assertTrue(p.netherHeightRamp(1260) > 0.0); // shifted: the nether band has begun
        assertEquals(p.netherHeightRamp(1260), p.netherHeightRamp(1260 + PERIOD), EPS); // shift is constant across cycles
    }

    @Test
    @DisplayName("a disabled phase collapses to zero length")
    void disabledCollapse() {
        WorldGenCycle endOnly = new WorldGenCycle(0L, 300, 0, new int[] {1, 5, 20}, 0, 0, 0, 0, 100, 40, 200, 0, 0, 0, 0);
        assertEquals(0L, endOnly.netherLen());
        assertEquals(680L, endOnly.endLen());
        assertEquals(2L * 300 + 680, endOnly.period());
        assertEquals(0.0, endOnly.netherHeightRamp(500), EPS);
        assertEquals(1.0, endOnly.netherMountainMultiplier(500), EPS);
    }
}
