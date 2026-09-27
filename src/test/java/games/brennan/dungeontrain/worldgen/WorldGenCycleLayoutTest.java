package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.CycleLayout.Style;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacySpan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorldGenCycle} driven by the shipped {@link CycleLayout}: each band's queries at both
 * occurrences, the End → upside-down vs End → spheres adjacency, per-occurrence passes, the legacy
 * crossfades, and the same answers a run later at twice the distance.
 */
final class WorldGenCycleLayoutTest {

    private static final long START = 10_000L;
    private static final CycleLayout LAYOUT = CycleLayoutTest.shipped();
    private static final long P = LAYOUT.period();

    /** The shipped defaults: stage 40 × {1,2,4,8,15}, beach 32, core fade 300; End 120/500; UD 600, exit 600, exit fade 10 000. */
    private static final WorldGenCycle C = new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
            120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 6550, 750, 5000, 8000, 1500, 10_000, 0.08,
            CycleLayoutTest.eraDefaults(), LAYOUT, 0);

    private static WorldGenCycle custom(String order) {
        CycleLayout l = CycleLayout.parse(order, CycleLayoutTest.FADES, CycleLayoutTest.eraDefaults(), t -> true, w -> {});
        return new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
                120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 6550, 750, 5000, 8000, 1500, 10_000, 0.08,
                CycleLayoutTest.eraDefaults(), l, 0);
    }

    /** World X of base coordinate {@code u} on run {@code k}. */
    private static int x(long u, int k) {
        return (int) (START + CycleLayout.runStart(k, P) + (u << k));
    }

    private static int x(long u) {
        return x(u, 0);
    }

    @Test
    @DisplayName("period is the run-1 length and the layout is reported")
    void period() {
        assertTrue(C.hasLayout());
        assertEquals(117_028L, C.period());
        assertEquals(232, C.riseLen());
    }

    @Test
    @DisplayName("both Nether occurrences ramp from their own slot; the second is 8000 long and BetterNether")
    void nether() {
        long n1 = LAYOUT.start(1);
        assertEquals(0.0, C.netherRamp(x(n1 - 1)));
        assertTrue(C.netherHeightRamp(x(n1 + 100)) > 0.0);
        assertTrue(C.isNetherCore(x(n1 + 232 + 300 + 10)));
        assertTrue(C.isNetherCore(x(n1 + 232 + 300 + 2999)));
        assertFalse(C.isNetherCore(x(n1 + 232 + 300 + 3000 + 10)));
        assertEquals(0.0, C.netherRamp(x(n1 + 4064)));
        assertEquals(Style.VANILLA, C.netherStyleAt(x(n1 + 1000)));
        assertNull(C.netherStyleAt(x(n1 - 10)));

        long n2 = LAYOUT.start(7);
        assertTrue(C.isNetherCore(x(n2 + 232 + 300 + 7999)));
        assertFalse(C.isNetherCore(x(n2 + 232 + 300 + 8000 + 10)));
        assertEquals(Style.BETTER, C.netherStyleAt(x(n2 + 1000)));
        assertEquals(7999L, C.netherCoreDepth(x(n2 + 232 + 300 + 7999)));
        assertEquals(x(n2), (int) C.netherBandEntranceX(x(n2 + 3000)));
        assertEquals(x(n1), (int) C.netherBandEntranceX(x(n1 + 3000)));
    }

    @Test
    @DisplayName("passes count occurrences: 0 and 1 on run 0, 2 and 3 on run 1; cycleIndex is the run")
    void passes() {
        assertEquals(0L, C.netherPassIndex(x(LAYOUT.start(1) + 100)));
        assertEquals(1L, C.netherPassIndex(x(LAYOUT.start(7) + 100)));
        assertEquals(2L, C.netherPassIndex(x(LAYOUT.start(1) + 100, 1)));
        assertEquals(3L, C.netherPassIndex(x(LAYOUT.start(7) + 100, 1)));
        assertEquals(0L, C.endPassIndex(x(LAYOUT.start(3) + 100)));
        assertEquals(1L, C.endPassIndex(x(LAYOUT.start(4) + 100)));    // the joined End's BoP piece
        assertEquals(2L, C.endPassIndex(x(LAYOUT.start(9) + 100)));
        assertEquals(3L, C.endPassIndex(x(LAYOUT.start(3) + 100, 1)));
        assertEquals(Style.VANILLA, C.endStyleOfPass(0L));
        assertEquals(Style.BOP, C.endStyleOfPass(1L));
        assertEquals(Style.BETTER, C.endStyleOfPass(2L));
        assertEquals(Style.VANILLA, C.endStyleOfPass(3L));              // the vanilla piece stays vanilla every run
        assertEquals(0L, C.cycleIndex(x(P - 1)));
        assertEquals(1L, C.cycleIndex(x(0, 1)));
        assertEquals(-1L, C.cycleIndex((int) START - 1));
        long[] r = C.netherPassRange(1);
        assertEquals(x(LAYOUT.start(7)), (int) r[0]);
        assertEquals(x(LAYOUT.start(7) + LAYOUT.length(7)), (int) r[1]);
        long[] r2 = C.netherPassRange(2);
        assertEquals(x(LAYOUT.start(1), 1), (int) r2[0]);
    }

    @Test
    @DisplayName("End: lap-1's joined vanilla/BoP End feeds the upside-down entry lead, lap-2 BetterEnd flows into the spheres")
    void endAdjacency() {
        long e1 = LAYOUT.start(3);
        long bop = LAYOUT.start(4);
        long e1end = bop + LAYOUT.length(4);
        assertTrue(C.isEndCore(x(e1 + 740 + 100)));
        assertEquals(Style.VANILLA, C.endStyleAt(x(e1 + 1000)));
        assertEquals(Style.BOP, C.endStyleAt(x(bop + 100)));
        assertTrue(C.isBopEndAt(x(bop + 100)));
        assertFalse(C.isBopEndAt(x(e1 + 1000)));
        // One band: the islands carry straight on across the vanilla → BoP seam, no void dip between.
        for (long u = e1 + 740; u < bop + 2000; u += 50) {
            assertEquals(1.0, C.endIslandRamp(x(u)), 1e-12, "u=" + u);
            assertTrue(C.isEndCore(x(u)), "u=" + u);
        }
        assertEquals(C.endMiddleRamp(x(bop - 1)), C.endMiddleRamp(x(bop)), 1e-12);
        assertEquals(C.endSkyRamp(x(bop - 1), 200), C.endSkyRamp(x(bop), 200), 1e-12);
        // Its exit side matches a lone 3000-core End's.
        assertEquals(0.0, C.endIslandRamp(x(bop + 2000 + 120)));
        assertEquals(0.0, C.endMiddleRamp(x(e1end)));
        assertTrue(C.endMiddleRamp(x(e1end - 1)) > 0.0);
        // Entry lead = min(udFade 600, eVoid 500) = 500, the last 500 of the joined End.
        assertEquals(500L, C.udEntryLeadLen());
        assertFalse(C.isInUpsideDownEntryLead(x(e1end - 501)));
        assertTrue(C.isInUpsideDownEntryLead(x(e1end - 500)));
        assertTrue(C.isInUpsideDownEntryLead(x(e1end - 1)));
        assertFalse(C.isInUpsideDownEntryLead(x(e1end)));
        assertFalse(C.isInUpsideDownEntryLead(x(bop - 1)));             // not at the seam: the UD follows the BoP piece
        assertTrue(C.upsideDownEntryRevealRamp(x(e1end - 1)) > 0.99);

        long e2 = LAYOUT.start(9);
        long e2len = LAYOUT.length(9);
        assertEquals(Style.BETTER, C.endStyleAt(x(e2 + 1000)));
        assertTrue(C.isEndCore(x(e2 + 740 + 7999)));
        assertFalse(C.isInUpsideDownEntryLead(x(e2 + e2len - 1)));     // spheres follow, not the upside-down
        assertTrue(C.isInSpheresFade(x(e2 + e2len)));
        assertTrue(C.isInSpheresApproachOrBand(x(e2 + e2len)));
        assertFalse(C.isInSpheresApproachOrBand(x(e2 + e2len - 1)));
    }

    @Test
    @DisplayName("the vanilla → BoP End seam crossfades: BoP's share of columns rises across ±200, none outside")
    void endSeamCrossfade() {
        long seed = 1450L;
        long seam = LAYOUT.start(4);
        int half = WorldGenCycle.END_SEAM_HALF_BLEND;
        double prev = -1.0;
        for (long u = seam - half; u < seam + half; u += 40) {
            int bop = 0;
            int n = 0;
            for (long du = 0; du < 40; du++) {
                for (int z = -128; z < 128; z += 2) {
                    long pass = C.endSourcePassAt(x(u + du), z, seed);
                    assertTrue(pass == 0L || pass == 1L, "pass " + pass);
                    if (pass == 1L) bop++;
                    n++;
                }
            }
            double share = (double) bop / n;
            assertTrue(share >= prev - 0.05, "BoP share should rise: " + prev + " -> " + share + " at " + u);
            prev = share;
        }
        assertTrue(prev > 0.9, "almost all BoP at the far edge: " + prev);
        for (int z = -64; z < 64; z++) {
            assertEquals(0L, C.endSourcePassAt(x(seam - half - 1), z, seed));
            assertEquals(1L, C.endSourcePassAt(x(seam + half), z, seed));
            // away from any seam it is just the pass at X — the lone BetterEnd, and before the band
            assertEquals(C.endPassIndex(x(LAYOUT.start(9) + 3000)), C.endSourcePassAt(x(LAYOUT.start(9) + 3000), z, seed));
        }
        // seed-stable, and it doubles with the run like everything else
        assertEquals(C.endSourcePassAt(x(seam), 7, seed), C.endSourcePassAt(x(seam), 7, seed));
        assertEquals(Style.VANILLA, C.endSourceLookAt(x(seam - half - 1, 1), 0, seed));
        assertEquals(Style.BOP, C.endSourceLookAt(x(seam + half, 1), 0, seed));
    }

    @Test
    @DisplayName("the WWOO stretch runs up to the joined End; it bleeds only into the End's entry fade")
    void wwooBleed() {
        long w = LAYOUT.start(2);
        assertEquals(Style.WWOO, C.overworldStyleAt(x(w + 100)));
        assertEquals(Style.WWOO, C.bleedingOverworldStyleAt(x(LAYOUT.start(3) + 10)));
        assertNull(C.bleedingOverworldStyleAt(x(LAYOUT.start(4) - 10)));   // the seam is End, not a gap edge
        assertNull(C.bleedingOverworldStyleAt(x(LAYOUT.start(4) + 10)));
        // The UD follows, not a modded gap: nothing to bleed on the exit side.
        assertNull(C.bleedingOverworldStyleAt(x(LAYOUT.start(5) - 10)));
    }

    @Test
    @DisplayName("Lost City is its own legacy run between BetterNether and BetterEnd")
    void lostCityRun() {
        long lc = LAYOUT.start(8);
        assertEquals(LAYOUT.start(7) + LAYOUT.length(7), lc);
        assertNull(C.legacyAt(x(lc - 1)));
        WorldGenCycle.LegacyHit entry = C.legacyAt(x(lc + 10));
        assertNull(entry.from());
        assertEquals(LegacyBandKind.LOST_CITY, entry.to());
        assertTrue(C.isInLegacyBand(LegacyBandKind.LOST_CITY, x(lc + 480)));
        assertFalse(C.isInLegacyBand(LegacyBandKind.LOST_CITY, x(lc + 480 + 4000)));
        WorldGenCycle.LegacyHit exit = C.legacyAt(x(lc + 480 + 4000 + 240));
        assertEquals(LegacyBandKind.LOST_CITY, exit.from());
        assertNull(exit.to());
        assertNull(C.legacyAt(x(LAYOUT.start(9))));
        assertEquals(4000L, C.legacyLen(LegacyBandKind.LOST_CITY));
        assertEquals(x(lc + 480), (int) C.legacyCoreStartX(LegacyBandKind.LOST_CITY, x(lc + 100)));
        // The other eras don't answer inside Lost City's run, and Lost City doesn't answer in theirs.
        assertEquals(WorldGenCycle.NOT_IN_LEGACY_SLOT, C.legacyCoreStartX(LegacyBandKind.AMPLIFIED, x(lc + 1000)));
        assertEquals(WorldGenCycle.NOT_IN_LEGACY_SLOT, C.legacyCoreStartX(LegacyBandKind.LOST_CITY, x(LAYOUT.start(12) + 1000)));
        assertTrue(Double.isNaN(C.legacyCoreProgress(LegacyBandKind.AMPLIFIED, x(lc + 1000))));
        assertEquals(-1.0, C.legacyProgress(LegacyBandKind.BETA, x(lc + 1000)));
        assertTrue(C.isInLegacyApproachOrBand(LegacyBandKind.LOST_CITY, x(lc + 10)));
        assertFalse(C.isInLegacyApproachOrBand(LegacyBandKind.LOST_CITY, x(LAYOUT.start(12) + 10)));
        assertFalse(C.isInLegacyApproachOrBand(LegacyBandKind.AMPLIFIED, x(lc + 10)));
    }

    @Test
    @DisplayName("upside-down: fades, a 2500 core, a 6000 Reassembly and the exit gap")
    void upsideDown() {
        long u = LAYOUT.start(5);
        assertEquals(0.0, C.upsideDownRamp(x(u - 1)));
        assertEquals(0.5, C.upsideDownRamp(x(u + 300)), 1e-9);
        assertEquals(1.0, C.upsideDownRamp(x(u + 600 + 1250)));
        assertTrue(C.isInUpsideDownBand(x(u + 600 + 2500 + 599)));
        assertFalse(C.isInUpsideDownBand(x(u + 600 + 2500 + 600)));
        assertTrue(C.isInUpsideDownExitFade(x(u + 3700)));
        assertTrue(C.isInUpsideDownExitFade(x(u + 3700 + 5999)));
        assertFalse(C.isInUpsideDownExitFade(x(u + 3700 + 6000)));
        assertEquals(0.5, C.upsideDownExitOwRevealRamp(x(u + 3700 + 3000)), 1e-9);
        assertEquals(0.5, C.upsideDownExitMirrorDisperseRamp(x(u + 3700 + 3000)), 1e-9);
        assertEquals(0.0, C.upsideDownRamp(x(u + 3700 + 6000 + 10)));   // the 600 exit gap
        assertEquals(LAYOUT.start(6), u + 600 + 2500 + 600 + 6000 + 600);
    }

    @Test
    @DisplayName("spheres fade in over 750, chuncks and stacks over 1500, then hold their cores")
    void fadeInBands() {
        long s = LAYOUT.start(10);
        assertEquals(0.0, C.spheresVoidRamp(x(s - 1)));
        assertEquals(0.5, C.spheresVoidRamp(x(s + 375)), 1e-9);
        assertTrue(C.isInSpheresBand(x(s + 750)));
        assertEquals(6_550L, C.spheresLen());
        assertEquals(6_549L, C.spheresCoreOffset(x(s + 750 + 6_549)));
        assertFalse(C.isInSpheresBand(x(s + 750 + 6_550)));
        long c = LAYOUT.start(14);
        assertEquals(1.0, C.chuncksKeepDensityAt(x(c - 1)));
        assertEquals(0.3, C.chuncksKeepDensityAt(x(c + 1500 + 10)), 1e-9);
        assertTrue(C.isInChuncksBand(x(c + 1500)));
        assertTrue(C.isInChuncksApproachOrBand(x(c - 500)));                 // the 650-block OW gap before it
        long m = LAYOUT.start(15);                                     // the mix zone: 2000 of chuncks core, then it
        assertTrue(C.isInChuncksBand(x(m - 1)));
        assertFalse(C.isInChuncksBand(x(m)));
        assertTrue(C.isInMixZone(x(m)));
        assertFalse(C.isInMixZone(x(m - 1)));
        assertTrue(C.isInMixZone(x(m + 3999)));
        long st = LAYOUT.start(16);                                    // straight after the mix zone
        assertFalse(C.isInMixZone(x(st)));
        assertTrue(C.isInMixStacksCrossfade(x(st)));                   // the stacks fade after mix takes mix picks
        assertTrue(C.mixPicksAt(x(st + 1499)));
        assertFalse(C.mixPicksAt(x(st + 1500)));
        assertFalse(C.isInChuncksStacksCrossfade(x(st)));
        assertEquals(1.0, C.chuncksKeepDensityAt(x(st + 10)));
        // Chuncks straight into stacks (a custom order) carries on at its core density under the stacks fade;
        // an overworld gap between them keeps the plain-terrain entry fade.
        WorldGenCycle direct = custom("ow:100, chuncks:5000, stacks:5000");
        int dst = (int) (START + direct.layout().start(2));
        assertTrue(direct.isInChuncksStacksCrossfade(dst + 10));
        assertEquals(0.3, direct.chuncksKeepDensityAt(dst + 1499), 1e-9);
        assertFalse(direct.isInChuncksStacksCrossfade(dst + 1500));
        assertEquals(1.0, direct.chuncksKeepDensityAt(dst + 1500));
        WorldGenCycle g = custom("ow:100, chuncks:5000, ow:500, stacks:5000");
        int gst = (int) (START + g.layout().start(3));
        assertFalse(g.isInChuncksStacksCrossfade(gst + 10));
        assertEquals(1.0, g.chuncksKeepDensityAt(gst + 10));
        assertEquals(1.0, C.stacksVoidRampAt(x(st + 1500 + 4999)));
        assertTrue(C.isInStacksBand(x(st + 1500 + 4999)));
        assertEquals(0.0, C.stacksVoidRampAt(x(st + 1500 + 5000)));           // run 1 starts here
        assertEquals(x(0, 1), x(st + 1500 + 5000));
    }

    @Test
    @DisplayName("legacy run: entry fade, era cores, era-to-era crossfades that are never modern, exit fade")
    void legacy() {
        long l = LAYOUT.start(12);
        assertNull(C.legacyAt(x(l - 1)));
        WorldGenCycle.LegacyHit entry = C.legacyAt(x(l + 10));
        assertNotNull(entry);
        assertNull(entry.from());
        assertEquals(LegacyBandKind.AMPLIFIED, entry.to());
        assertTrue(entry.t() > 0.0 && entry.t() < 0.1);
        WorldGenCycle.LegacyHit core = C.legacyAt(x(l + 480 + 100));
        assertEquals(LegacyBandKind.AMPLIFIED, core.from());
        assertEquals(LegacyBandKind.AMPLIFIED, core.to());
        assertEquals(1.0, core.t());
        WorldGenCycle.LegacyHit cross = C.legacyAt(x(l + 480 + 5000 + 240));
        assertEquals(LegacyBandKind.AMPLIFIED, cross.from());
        assertEquals(LegacyBandKind.BETA, cross.to());                              // Lost City has its own run now
        assertEquals(0.5, cross.t(), 0.01);
        assertTrue(C.isInLegacyBand(LegacyBandKind.AMPLIFIED, x(l + 480)));
        assertFalse(C.isInLegacyBand(LegacyBandKind.AMPLIFIED, x(l + 480 + 5000)));
        long beta = l + 480 + 5000 + 480;                                            // Beta core start, straight after Amplified
        assertFalse(C.isInLegacyBand(LegacyBandKind.LOST_CITY, x(beta)));
        assertTrue(C.isInLegacyBand(LegacyBandKind.BETA, x(beta)));
        assertFalse(C.isInLegacyBand(LegacyBandKind.LARGE_BIOMES, x(l + 480)));   // built, not shipped
        assertEquals(4320L, C.legacyLen(LegacyBandKind.FAR_LANDS));
        assertEquals(x(beta), (int) C.legacyCoreStartX(LegacyBandKind.BETA, x(beta - 380)));
        assertEquals(WorldGenCycle.NOT_IN_LEGACY_SLOT, C.legacyCoreStartX(LegacyBandKind.BETA, x(l + 100)));
        assertEquals(0.5, C.legacyCoreProgress(LegacyBandKind.AMPLIFIED, x(l + 480 + 2500)), 1e-9);
        long chaos = beta + 3500 + 480 + 4320 + 480;                                 // Caves of Chaos core start, after the Far Lands
        WorldGenCycle.LegacyHit cross2 = C.legacyAt(x(chaos - 240));
        assertEquals(LegacyBandKind.FAR_LANDS, cross2.from());
        assertEquals(LegacyBandKind.CAVES_OF_CHAOS, cross2.to());
        assertTrue(C.isInLegacyBand(LegacyBandKind.CAVES_OF_CHAOS, x(chaos)));
        assertEquals(4000L, C.legacyLen(LegacyBandKind.CAVES_OF_CHAOS));
        assertFalse(C.isInLegacyBand(LegacyBandKind.CAVES_OF_CHAOS, x(chaos + 4000)));
        assertTrue(C.legacyProgress(LegacyBandKind.ALPHA,
                x(l + LAYOUT.eraCoreStart(12, LAYOUT.eraIndex(LegacyBandKind.ALPHA)))) > 0.0);
        int flat = LAYOUT.eraIndex(LegacyBandKind.SUPERFLAT);
        assertEquals(LAYOUT.eraIndex(LegacyBandKind.VOID) - 1, flat);                  // Superflat runs just before Void
        assertTrue(C.isInLegacyBand(LegacyBandKind.SUPERFLAT, x(l + LAYOUT.eraCoreStart(12, flat))));
        assertEquals(1000L, C.legacyLen(LegacyBandKind.SUPERFLAT));
        long end = l + LAYOUT.length(12);
        WorldGenCycle.LegacyHit exit = C.legacyAt(x(end - 1));
        assertEquals(LegacyBandKind.VOID, exit.from());
        assertNull(exit.to());
        assertTrue(exit.t() > 0.99);
        assertNull(C.legacyAt(x(end)));
        assertTrue(C.isInLegacyApproachOrBand(LegacyBandKind.CLASSIC, x(l - 100)));   // the OW gap after the spheres
        assertEquals(LAYOUT.length(8) + LAYOUT.length(12), C.legacyTotalLen());
    }

    @Test
    @DisplayName("run 1 stretches everything ×2: the same base answers at twice the distance")
    void doubling() {
        long n2 = LAYOUT.start(7);
        for (long u : new long[] {0, n2, n2 + 232 + 300, n2 + 3000, n2 + 9000, LAYOUT.start(12) + 5000, P - 1}) {
            assertEquals(C.netherRamp(x(u)), C.netherRamp(x(u, 1)), 1e-12);
            assertEquals(C.netherRamp(x(u)), C.netherRamp(x(u, 1) + 1), 1e-12);   // the odd block shares its base
            assertEquals(C.spheresVoidRamp(x(u)), C.spheresVoidRamp(x(u, 2)), 1e-12);
            assertEquals(C.isInLegacyBand(LegacyBandKind.BETA, x(u)), C.isInLegacyBand(LegacyBandKind.BETA, x(u, 2)));
        }
        // Block-length queries scale: the Far Lands script covers twice the ground on run 1.
        long fl = LAYOUT.start(12) + LAYOUT.eraCoreStart(12, LAYOUT.eraIndex(LegacyBandKind.FAR_LANDS));
        assertEquals(4320L, C.legacyCoreLenBlocks(LegacyBandKind.FAR_LANDS, x(fl + 10)));
        assertEquals(8640L, C.legacyCoreLenBlocks(LegacyBandKind.FAR_LANDS, x(fl + 10, 1)));
        assertEquals(x(fl, 1), (int) C.legacyCoreStartX(LegacyBandKind.FAR_LANDS, x(fl + 10, 1)));
        assertEquals(x(n2, 1), (int) C.netherBandEntranceX(x(n2 + 100, 1)));
    }

    @Test
    @DisplayName("influence early-outs agree with the ramps across a run boundary")
    void influence() {
        for (long u = 0; u < P; u += 97) {
            int wx = x(u);
            boolean nether = C.netherRamp(wx) > 0.0 || C.netherHeightRamp(wx) > 0.0;
            if (nether) assertTrue(C.netherInfluence(wx, 0), "u=" + u);
            boolean end = C.endMiddleRamp(wx) > 0.0 || C.endIslandRamp(wx) > 0.0;
            if (end) assertTrue(C.endSegmentInfluence(wx), "u=" + u);
        }
        assertFalse(C.netherInfluence(x(1000), 100));
        assertTrue(C.netherInfluence(x(2740), 20));
        assertFalse(C.endSegmentInfluence(x(11_250)));
        assertTrue(C.endSegmentInfluence(x(11_314)));
        assertTrue(C.netherInfluence(x(P - 1), 2));           // straddles the run boundary: conservative true
    }

    @Test
    @DisplayName("LegacyHit keeps the one-band view")
    void legacyHitView() {
        WorldGenCycle.LegacyHit exit = new WorldGenCycle.LegacyHit(LegacyBandKind.BETA, null, 0.25);
        assertEquals(LegacyBandKind.BETA, exit.kind());
        assertEquals(0.75, exit.ramp(), 1e-12);
        WorldGenCycle.LegacyHit entry = new WorldGenCycle.LegacyHit(LegacyBandKind.BETA, 0.3);
        assertNull(entry.from());
        assertEquals(0.3, entry.ramp(), 1e-12);
        WorldGenCycle.LegacyHit core = new WorldGenCycle.LegacyHit(LegacyBandKind.BETA, 1.0);
        assertEquals(LegacyBandKind.BETA, core.from());
        LegacySpan[] eras = LAYOUT.eras(12);
        assertEquals(LegacyBandKind.VOID, eras[eras.length - 1].kind());
    }
}
