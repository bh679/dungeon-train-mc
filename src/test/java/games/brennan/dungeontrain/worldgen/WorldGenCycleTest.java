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
    @DisplayName("cycleIndex: -1 before the anchor, 0 across the first period, +1 each repeat; endPassIndex is an alias")
    void cycleIndex() {
        // Before the anchor the cycle hasn't started.
        assertEquals(-1L, C.cycleIndex(0));
        assertEquals(-1L, C.cycleIndex(999));
        // First period [1000, 2940): index 0 — including the FIRST nether band (worldX [1300,1960)).
        assertEquals(0L, C.cycleIndex(1000));
        assertEquals(0L, C.cycleIndex(1500));                  // deep in the first nether band
        assertEquals(0L, C.cycleIndex(1000 + PERIOD - 1));
        // Second period [2940, 4880): index 1 — including the SECOND nether band, PERIOD blocks on.
        // This is the gate "Nether Return Again" keys off (netherPassIndex ≥ 1).
        assertEquals(1L, C.cycleIndex(1000 + PERIOD));
        assertEquals(1L, C.cycleIndex(1500 + PERIOD));         // deep in the second nether band
        assertEquals(2L, C.cycleIndex(1000 + 2 * PERIOD));
        // endPassIndex is a straight alias of cycleIndex (unchanged End-biome behaviour).
        for (int wx : new int[] {999, 1000, 1500, 1500 + PERIOD, 1000 + 2 * PERIOD}) {
            assertEquals(C.cycleIndex(wx), C.endPassIndex(wx), "endPassIndex alias @" + wx);
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
    @DisplayName("chuncks band: disabled is byte-identical; enabled sits after the upside-down exit gap with an entry fade then a full-density core")
    void chuncksBand() {
        // Disabled (C has chuncksHold=0): zero length, period unchanged, never in-band, density 1.0.
        assertEquals(0L, C.chuncksLen());
        assertEquals(0L, C.chuncksFadeLen());
        assertEquals(PERIOD, C.period());
        org.junit.jupiter.api.Assertions.assertFalse(C.isInChuncksBand(3390));
        assertEquals(1.0, C.chuncksKeepDensityAt(3390), EPS);

        // Same base as upsideDownBand()'s `u` (udFade 50, udHold 200 → udLen 300; udExit 150), plus a
        // 200-block entry fade + a 500-block full-density core (20-arg canonical ctor). Fade begins right
        // after the upside-down exit gap: fadeStart offset = udStart 1940 + udLen 300 + udExitFade 0 +
        // udExit 150 = 2390; core begins at offset 2590. World-X: fade [3390,3590), core [3590,4090).
        WorldGenCycle c = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200,
                100, 40, 200, 50, 200, 150, 0, 500, 200, 0, 0.12, 0.5, 0);
        assertEquals(500L, c.chuncksLen());
        assertEquals(200L, c.chuncksFadeLen());
        assertEquals(0L, c.chuncksLeadGapLen());                         // no lead gap in this case
        assertEquals(3090L, c.period());                                 // 2390 (u's period) + fade 200 + core 500
        assertEquals(0.12, c.chuncksKeepDensity(), EPS);                 // record accessors carry the knobs
        assertEquals(0.5, c.chuncksSliceRatio(), EPS);

        // isInChuncksBand is the CORE only (not the fade).
        org.junit.jupiter.api.Assertions.assertFalse(c.isInChuncksBand(3389)); // upside-down exit gap (OW)
        org.junit.jupiter.api.Assertions.assertFalse(c.isInChuncksBand(3400)); // in the entry fade, not the core
        org.junit.jupiter.api.Assertions.assertTrue(c.isInChuncksBand(3590));  // core entry
        org.junit.jupiter.api.Assertions.assertTrue(c.isInChuncksBand(4089));  // core end
        org.junit.jupiter.api.Assertions.assertFalse(c.isInChuncksBand(4090)); // wraps into the next period's leading owGap

        // Entry transition: keep-density is 1.0 before the fade, ramps 1→0.12 across it, holds 0.12 in
        // the core, and returns to 1.0 after (hard far edge).
        assertEquals(1.0, c.chuncksKeepDensityAt(3389), EPS);            // before the fade
        assertEquals(1.0, c.chuncksKeepDensityAt(3390), EPS);            // fade start (t=0)
        assertEquals(1.0 + (0.12 - 1.0) * 0.5, c.chuncksKeepDensityAt(3490), EPS); // fade midpoint (100/200)
        assertEquals(0.12, c.chuncksKeepDensityAt(3590), EPS);           // core start (full density)
        assertEquals(0.12, c.chuncksKeepDensityAt(3800), EPS);           // deep in the core
        assertEquals(1.0, c.chuncksKeepDensityAt(4090), EPS);            // past the core — hard far edge

        // Disjoint from the other segments.
        org.junit.jupiter.api.Assertions.assertFalse(c.isInChuncksBand(1530)); // nether core
        org.junit.jupiter.api.Assertions.assertFalse(c.isInChuncksBand(2500)); // End core
        org.junit.jupiter.api.Assertions.assertFalse(c.isInChuncksBand(3090)); // upside-down core

        // Repeats forever with the new period.
        org.junit.jupiter.api.Assertions.assertTrue(c.isInChuncksBand(3590 + 3090));
        assertEquals(c.chuncksKeepDensityAt(3490), c.chuncksKeepDensityAt(3490 + 3090), EPS);
    }

    @Test
    @DisplayName("chuncks band sits after the End even when the upside-down band is disabled; zero fade is a hard edge")
    void chuncksWithoutUpsideDown() {
        // No upside-down (udFade/udHold/udExit/udExitFade all 0); chuncks core 500, fade 0 (hard edge).
        // All upside-down spans collapse, so the core = udStart = 2·owGap + netherLen + endLen = 1940 →
        // world-X 2940.
        WorldGenCycle c = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200,
                100, 40, 200, 0, 0, 0, 0, 500, 0, 0, 0.12, 0.5, 0);
        assertEquals(0L, c.upsideDownLen());
        assertEquals(500L, c.chuncksLen());
        assertEquals(0L, c.chuncksFadeLen());
        assertEquals(PERIOD + 500L, c.period());
        org.junit.jupiter.api.Assertions.assertFalse(c.isInChuncksBand(2939)); // End band proper
        assertEquals(1.0, c.chuncksKeepDensityAt(2939), EPS);                  // hard edge — no fade before the core
        org.junit.jupiter.api.Assertions.assertTrue(c.isInChuncksBand(2940));  // chuncks starts right after End
        assertEquals(0.12, c.chuncksKeepDensityAt(2940), EPS);
        org.junit.jupiter.api.Assertions.assertTrue(c.isInChuncksBand(3439));  // band end (2940+500-1)
        org.junit.jupiter.api.Assertions.assertFalse(c.isInChuncksBand(3440)); // wraps
    }

    @Test
    @DisplayName("chuncks lead gap: overworld inserted before the entry fade, shifting the band and growing the period")
    void chuncksLeadGap() {
        // Same base as chuncksBand()'s `c` (fade 200, core 500) plus a 300-block overworld lead-in gap
        // before the fade. fadeStart offset = 2390 (chuncksBand's fadeStart) + leadGap 300 = 2690; core at
        // 2890. World-X (phaseShift 0): lead gap [3390,3690), fade [3690,3890), core [3890,4390).
        WorldGenCycle c = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200,
                100, 40, 200, 50, 200, 150, 0, 500, 200, 300, 0.12, 0.5, 0);
        assertEquals(300L, c.chuncksLeadGapLen());
        assertEquals(3390L, c.period());                                 // 3090 (chuncksBand) + leadGap 300

        // The lead gap is plain overworld — not in the band, keep-density 1.0 (no void), before the fade.
        assertEquals(1.0, c.chuncksKeepDensityAt(3390), EPS);            // lead gap start
        assertEquals(1.0, c.chuncksKeepDensityAt(3689), EPS);            // lead gap end
        org.junit.jupiter.api.Assertions.assertFalse(c.isInChuncksBand(3689));

        // Fade then core sit AFTER the lead gap (shifted +300 vs the no-lead case).
        assertEquals(1.0, c.chuncksKeepDensityAt(3690), EPS);            // fade start (t=0)
        assertEquals(0.12, c.chuncksKeepDensityAt(3890), EPS);           // core start
        org.junit.jupiter.api.Assertions.assertFalse(c.isInChuncksBand(3889)); // still fade
        org.junit.jupiter.api.Assertions.assertTrue(c.isInChuncksBand(3890));  // core start
        org.junit.jupiter.api.Assertions.assertTrue(c.isInChuncksBand(4389));  // core end
        org.junit.jupiter.api.Assertions.assertFalse(c.isInChuncksBand(4390)); // wraps
    }

    @Test
    @DisplayName("ocean band: disabled is byte-identical; enabled sits after the upside-down exit gap and BEFORE the chuncks band")
    void oceanBand() {
        // Disabled (C has oceanHold=0): zero length, period unchanged, never in-band.
        assertEquals(0L, C.oceanLen());
        assertEquals(0L, C.oceanLeadGapLen());
        assertEquals(PERIOD, C.period());
        org.junit.jupiter.api.Assertions.assertFalse(C.isInOceanBand(3490));

        // Same base as chuncksBand()'s `c` (ud + chuncks), plus an ocean band of 400 core + 100 lead gap
        // inserted between the upside-down exit gap and the chuncks lead gap (24-arg canonical ctor).
        // Offsets: udStart 1940 + udLen 300 + udExitFade 0 + udExit 150 = 2390 (ocean lead-gap start);
        // ocean core at offset 2490..2890; chuncks fade then follows. World-X (phaseShift 0, startX 1000):
        // ocean lead gap [3390,3490), ocean core [3490,3890), chuncks fade [3890,4090), chuncks core [4090,4590).
        WorldGenCycle o = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200,
                100, 40, 200, 50, 200, 150, 0, 500, 200, 0, 0.12, 0.5, 400, 100, 0);
        assertEquals(400L, o.oceanLen());
        assertEquals(100L, o.oceanLeadGapLen());
        assertEquals(3590L, o.period());   // 1940 + ud 300 + udExit 150 + ocean(100+400) + chuncks(200+500)

        // isInOceanBand is the CORE only; the lead gap before it is plain overworld.
        org.junit.jupiter.api.Assertions.assertFalse(o.isInOceanBand(3489)); // ocean lead gap (OW)
        org.junit.jupiter.api.Assertions.assertTrue(o.isInOceanBand(3490));  // core entry
        org.junit.jupiter.api.Assertions.assertTrue(o.isInOceanBand(3889));  // core end
        org.junit.jupiter.api.Assertions.assertFalse(o.isInOceanBand(3890)); // chuncks entry fade begins

        // Ocean sits BEFORE chuncks and is disjoint from it and every other segment.
        org.junit.jupiter.api.Assertions.assertFalse(o.isInChuncksBand(3490)); // this X is ocean, not chuncks
        org.junit.jupiter.api.Assertions.assertTrue(o.isInChuncksBand(4090));  // chuncks core now sits after ocean
        org.junit.jupiter.api.Assertions.assertFalse(o.isInOceanBand(1530));   // nether core
        org.junit.jupiter.api.Assertions.assertFalse(o.isInOceanBand(2500));   // End core
        org.junit.jupiter.api.Assertions.assertFalse(o.isInOceanBand(3090));   // upside-down core

        // firstWorldXInPhase(OCEAN) lands a representative in-band column (the core centre).
        long ox = o.firstWorldXInPhase(TrainPhase.OCEAN);
        assertEquals(3690L, ox);
        org.junit.jupiter.api.Assertions.assertTrue(o.isInOceanBand((int) ox));

        // Repeats forever with the new period.
        org.junit.jupiter.api.Assertions.assertTrue(o.isInOceanBand(3490 + 3590));
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
