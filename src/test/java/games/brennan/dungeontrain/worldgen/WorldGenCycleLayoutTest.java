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
            120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 12_000, 1500, 5000, 8000, 1500, 10_000, 0.08,
            CycleLayoutTest.eraDefaults(), LAYOUT, 0);

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
        assertEquals(132_693L, C.period());
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

        long n2 = LAYOUT.start(6);
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
        assertEquals(1L, C.netherPassIndex(x(LAYOUT.start(6) + 100)));
        assertEquals(2L, C.netherPassIndex(x(LAYOUT.start(1) + 100, 1)));
        assertEquals(3L, C.netherPassIndex(x(LAYOUT.start(6) + 100, 1)));
        assertEquals(0L, C.endPassIndex(x(LAYOUT.start(3) + 100)));
        assertEquals(1L, C.endPassIndex(x(LAYOUT.start(8) + 100)));
        assertEquals(0L, C.cycleIndex(x(P - 1)));
        assertEquals(1L, C.cycleIndex(x(0, 1)));
        assertEquals(-1L, C.cycleIndex((int) START - 1));
        long[] r = C.netherPassRange(1);
        assertEquals(x(LAYOUT.start(6)), (int) r[0]);
        assertEquals(x(LAYOUT.start(6) + LAYOUT.length(6)), (int) r[1]);
        long[] r2 = C.netherPassRange(2);
        assertEquals(x(LAYOUT.start(1), 1), (int) r2[0]);
    }

    @Test
    @DisplayName("End: lap-1 End feeds the upside-down entry lead, lap-2 BetterEnd flows into the spheres")
    void endAdjacency() {
        long e1 = LAYOUT.start(3);
        long e1len = LAYOUT.length(3);
        assertTrue(C.isEndCore(x(e1 + 740 + 100)));
        assertEquals(Style.VANILLA, C.endStyleAt(x(e1 + 1000)));
        // Entry lead = min(udFade 600, eVoid 500) = 500, the last 500 of the End span.
        assertEquals(500L, C.udEntryLeadLen());
        assertFalse(C.isInUpsideDownEntryLead(x(e1 + e1len - 501)));
        assertTrue(C.isInUpsideDownEntryLead(x(e1 + e1len - 500)));
        assertTrue(C.isInUpsideDownEntryLead(x(e1 + e1len - 1)));
        assertFalse(C.isInUpsideDownEntryLead(x(e1 + e1len)));
        assertTrue(C.upsideDownEntryRevealRamp(x(e1 + e1len - 1)) > 0.99);

        long e2 = LAYOUT.start(8);
        long e2len = LAYOUT.length(8);
        assertEquals(Style.BETTER, C.endStyleAt(x(e2 + 1000)));
        assertTrue(C.isEndCore(x(e2 + 740 + 7999)));
        assertFalse(C.isInUpsideDownEntryLead(x(e2 + e2len - 1)));     // spheres follow, not the upside-down
        assertTrue(C.isInSpheresFade(x(e2 + e2len)));
        assertTrue(C.isInSpheresApproachOrBand(x(e2 + e2len)));
        assertFalse(C.isInSpheresApproachOrBand(x(e2 + e2len - 1)));
    }

    @Test
    @DisplayName("upside-down: fades, a 2500 core, a 6000 Reassembly and the exit gap")
    void upsideDown() {
        long u = LAYOUT.start(4);
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
        assertEquals(LAYOUT.start(5), u + 600 + 2500 + 600 + 6000 + 600);
    }

    @Test
    @DisplayName("spheres, chuncks and stacks fade in over 1500 then hold their cores")
    void fadeInBands() {
        long s = LAYOUT.start(9);
        assertEquals(0.0, C.spheresVoidRamp(x(s - 1)));
        assertEquals(0.5, C.spheresVoidRamp(x(s + 750)), 1e-9);
        assertTrue(C.isInSpheresBand(x(s + 1500)));
        assertEquals(15_000L, C.spheresLen());
        assertEquals(14_999L, C.spheresCoreOffset(x(s + 1500 + 14_999)));
        assertFalse(C.isInSpheresBand(x(s + 1500 + 15_000)));
        long c = LAYOUT.start(13);
        assertEquals(1.0, C.chuncksKeepDensityAt(x(c - 1)));
        assertEquals(0.3, C.chuncksKeepDensityAt(x(c + 1500 + 10)), 1e-9);
        assertTrue(C.isInChuncksBand(x(c + 1500)));
        assertTrue(C.isInChuncksApproachOrBand(x(c - 1000)));                // the OW gap before it
        long st = LAYOUT.start(15);
        assertEquals(1.0, C.stacksVoidRampAt(x(st + 1500 + 4999)));
        assertTrue(C.isInStacksBand(x(st + 1500 + 4999)));
        assertEquals(0.0, C.stacksVoidRampAt(x(st + 1500 + 5000)));           // run 1 starts here
        assertEquals(x(0, 1), x(st + 1500 + 5000));
    }

    @Test
    @DisplayName("legacy run: entry fade, era cores, era-to-era crossfades that are never modern, exit fade")
    void legacy() {
        long l = LAYOUT.start(11);
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
        assertEquals(LegacyBandKind.BETA, cross.to());
        assertEquals(0.5, cross.t(), 0.01);
        assertTrue(C.isInLegacyBand(LegacyBandKind.AMPLIFIED, x(l + 480)));
        assertFalse(C.isInLegacyBand(LegacyBandKind.AMPLIFIED, x(l + 480 + 5000)));
        assertTrue(C.isInLegacyBand(LegacyBandKind.BETA, x(l + 480 + 5000 + 480)));
        assertFalse(C.isInLegacyBand(LegacyBandKind.LARGE_BIOMES, x(l + 480)));   // built, not shipped
        assertEquals(4320L, C.legacyLen(LegacyBandKind.FAR_LANDS));
        assertEquals(x(l + 480 + 5000 + 480), (int) C.legacyCoreStartX(LegacyBandKind.BETA, x(l + 480 + 5000 + 100)));
        assertEquals(WorldGenCycle.NOT_IN_LEGACY_SLOT, C.legacyCoreStartX(LegacyBandKind.BETA, x(l + 100)));
        assertEquals(0.5, C.legacyCoreProgress(LegacyBandKind.AMPLIFIED, x(l + 480 + 2500)), 1e-9);
        long chaos = l + 480 + 5000 + 480 + 3500 + 480 + 4320 + 480;                 // Caves of Chaos core start, after the Far Lands
        WorldGenCycle.LegacyHit cross2 = C.legacyAt(x(chaos - 240));
        assertEquals(LegacyBandKind.FAR_LANDS, cross2.from());
        assertEquals(LegacyBandKind.CAVES_OF_CHAOS, cross2.to());
        assertTrue(C.isInLegacyBand(LegacyBandKind.CAVES_OF_CHAOS, x(chaos)));
        assertEquals(4000L, C.legacyLen(LegacyBandKind.CAVES_OF_CHAOS));
        assertFalse(C.isInLegacyBand(LegacyBandKind.CAVES_OF_CHAOS, x(chaos + 4000)));
        assertTrue(C.legacyProgress(LegacyBandKind.ALPHA,
                x(l + LAYOUT.eraCoreStart(LAYOUT.eraIndex(LegacyBandKind.ALPHA)))) > 0.0);
        int flat = LAYOUT.eraIndex(LegacyBandKind.SUPERFLAT);
        assertEquals(LAYOUT.eraIndex(LegacyBandKind.VOID) - 1, flat);                  // Superflat runs just before Void
        assertTrue(C.isInLegacyBand(LegacyBandKind.SUPERFLAT, x(l + LAYOUT.eraCoreStart(flat))));
        assertEquals(1000L, C.legacyLen(LegacyBandKind.SUPERFLAT));
        long end = l + LAYOUT.length(11);
        WorldGenCycle.LegacyHit exit = C.legacyAt(x(end - 1));
        assertEquals(LegacyBandKind.VOID, exit.from());
        assertNull(exit.to());
        assertTrue(exit.t() > 0.99);
        assertNull(C.legacyAt(x(end)));
        assertTrue(C.isInLegacyApproachOrBand(LegacyBandKind.CLASSIC, x(l - 100)));   // the OW gap after the spheres
        assertEquals(LAYOUT.length(11), C.legacyTotalLen());
    }

    @Test
    @DisplayName("run 1 stretches everything ×2: the same base answers at twice the distance")
    void doubling() {
        long n2 = LAYOUT.start(6);
        for (long u : new long[] {0, n2, n2 + 232 + 300, n2 + 3000, n2 + 9000, LAYOUT.start(11) + 5000, P - 1}) {
            assertEquals(C.netherRamp(x(u)), C.netherRamp(x(u, 1)), 1e-12);
            assertEquals(C.netherRamp(x(u)), C.netherRamp(x(u, 1) + 1), 1e-12);   // the odd block shares its base
            assertEquals(C.spheresVoidRamp(x(u)), C.spheresVoidRamp(x(u, 2)), 1e-12);
            assertEquals(C.isInLegacyBand(LegacyBandKind.BETA, x(u)), C.isInLegacyBand(LegacyBandKind.BETA, x(u, 2)));
        }
        // Block-length queries scale: the Far Lands script covers twice the ground on run 1.
        long fl = LAYOUT.start(11) + LAYOUT.eraCoreStart(2);
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
        assertFalse(C.endSegmentInfluence(x(9750)));
        assertTrue(C.endSegmentInfluence(x(9814)));
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
        LegacySpan[] eras = LAYOUT.eras();
        assertEquals(LegacyBandKind.VOID, eras[eras.length - 1].kind());
    }
}
