package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behind the anchor the shipped layout runs in reverse: an overworld buffer as long as the lead gap, then
 * backward run {@code k} — a copy of forward run {@code k} laid towards −X — so walking backwards meets the
 * slots last-first while every band keeps its +X orientation.
 */
final class WorldGenCycleMirrorTest {

    private static final long START = 10_000L;
    private static final CycleLayout LAYOUT = CycleLayoutTest.shipped();
    private static final long P = LAYOUT.period();
    private static final long BUFFER = LAYOUT.length(0);

    private static final WorldGenCycle C = new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
            120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 6550, 750, 5000, 8000, 1500, 10_000, 0.08,
            CycleLayoutTest.eraDefaults(), LAYOUT, 0);

    /** Forward world X of base coordinate {@code u} on run {@code k}. */
    private static int fx(long u, int k) {
        return (int) (START + CycleLayout.runStart(k, P) + (u << k));
    }

    /** Reversed world X of base coordinate {@code u} on backward run {@code k}. */
    private static int bx(long u, int k) {
        return (int) (START - BUFFER - CycleLayout.runStart(k + 1, P) + (u << k));
    }

    @Test
    @DisplayName("the lead-gap-long buffer behind spawn is plain overworld, outside the cycle")
    void buffer() {
        assertEquals(CycleLayout.Type.OVERWORLD, LAYOUT.slot(0).type());
        for (long x = START - BUFFER; x < START; x += 50) {
            assertEquals(-1, C.slotIndexAt((int) x));
            assertTrue(C.isOverworldGapAt((int) x));
            assertFalse(C.isMirroredAt(x));
            assertEquals(-1L, C.cycleIndex((int) x));
        }
        assertFalse(C.isMirroredAt(START - BUFFER));
        assertTrue(C.isMirroredAt(START - BUFFER - 1));
        assertFalse(C.isMirroredAt(START));
    }

    @Test
    @DisplayName("just past the buffer is the END of the last slot; walking back meets every slot last-first")
    void reverseOrder() {
        int last = LAYOUT.count() - 1;
        int first = (int) (START - BUFFER - 1);
        assertEquals(last, C.slotIndexAt(first));
        assertEquals(LAYOUT.length(last) - 1, C.slotLocal(first));

        List<Integer> seen = new ArrayList<>();
        for (long x = START - BUFFER - 1; x >= START - BUFFER - P; x--) {
            int i = C.slotIndexAt((int) x);
            if (i >= 0 && (seen.isEmpty() || seen.get(seen.size() - 1) != i)) seen.add(i);
        }
        List<Integer> expected = new ArrayList<>();
        for (int i = last; i >= 0; i--) if (LAYOUT.length(i) > 0L) expected.add(i);
        assertEquals(expected, seen);
        assertEquals(0L, C.cycleIndex((int) (START - BUFFER - P)));
        assertEquals(1L, C.cycleIndex((int) (START - BUFFER - P - 1)));
        assertEquals(last, C.slotIndexAt((int) (START - BUFFER - P - 1)));
    }

    @Test
    @DisplayName("every query at a reversed column equals the forward column with the same (run, base)")
    void matchesForward() {
        List<IntFunction<Object>> queries = List.of(
                C::slotIndexAt, C::slotLocal, C::runScaleAt, C::cycleIndex,
                C::netherRamp, C::netherHeightRamp, C::isNetherCore, C::netherCoreDepth,
                C::netherMountainMultiplier, C::isNetherBeachStage, C::netherStyleAt, C::netherPassIndex,
                C::endMiddleRamp, C::endIslandRamp, C::isEndCore, C::endStyleAt, C::endPassIndex,
                C::upsideDownRamp, C::isInUpsideDownBand, C::isInUpsideDownEntryLead, C::isInUpsideDownExitFade,
                C::isInChuncksBand, C::chuncksKeepDensityAt, C::isInMixZone, C::mixPicksAt,
                C::isInSpheresBand, C::spheresVoidRamp, C::isInStacksBand, C::stacksVoidRampAt,
                C::overworldStyleAt, C::bleedingOverworldStyleAt, C::overworldGapAt, C::isOverworldGapAt,
                x -> String.valueOf(C.legacyAt(x)));
        for (int k = 0; k <= 1; k++) {
            for (long u = 0; u < P; u += 37) {
                int f = fx(u, k);
                int b = bx(u, k);
                for (int q = 0; q < queries.size(); q++) {
                    assertEquals(queries.get(q).apply(f), queries.get(q).apply(b), "query " + q + " at u=" + u + " run " + k);
                }
                assertEquals(C.netherBandEntranceX(f) - f, C.netherBandEntranceX(b) - b, "entrance at u=" + u);
                assertEquals(bx(123, k), C.worldXOfBaseNear(b, 123));
                if (C.slotIndexAt(f) >= 0) assertEquals(C.slotWorldX(f, 5) - f, C.slotWorldX(b, 5) - b);
            }
        }
    }

    @Test
    @DisplayName("influence never misses a Nether/End column behind spawn (stride-1 sweep, margin 64)")
    void influenceIsConservative() {
        int margin = 64;
        long lo = START - BUFFER - P - 2 * margin;
        long hi = START + 2L * margin;
        int n = (int) (hi - lo + 1);
        boolean[] nether = new boolean[n];
        boolean[] end = new boolean[n];
        for (int j = 0; j < n; j++) {
            int i = C.slotIndexAt((int) (lo + j));
            CycleLayout.Type t = i < 0 ? null : LAYOUT.slot(i).type();
            nether[j] = t == CycleLayout.Type.NETHER || t == CycleLayout.Type.MIX;
            end[j] = t == CycleLayout.Type.END || t == CycleLayout.Type.MIX;
        }
        for (int j = margin; j < n - margin; j++) {
            long x = lo + j;
            boolean any = false;
            for (int w = j - margin; w <= j + margin && !any; w++) any = nether[w];
            if (any) assertTrue(C.netherInfluence(x, margin), "nether influence missed at " + x);
            if (end[j]) assertTrue(C.endSegmentInfluence(x), "end influence missed at " + x);
        }
        assertFalse(C.netherInfluence(START - BUFFER / 2, margin));
    }

    @Test
    @DisplayName("behind spawn each Nether / End copy keeps its own look, so the reverse journey can tell the first from the Better one")
    void reversedStylesPerCopy() {
        int nethers = 0;
        int ends = 0;
        for (int i = 0; i < LAYOUT.count(); i++) {
            CycleLayout.Slot slot = LAYOUT.slot(i);
            int mid = bx(LAYOUT.start(i) + LAYOUT.length(i) / 2, 0);
            boolean better = slot.styleOnRun(0) == CycleLayout.Style.BETTER;
            if (slot.type() == CycleLayout.Type.NETHER) {
                nethers++;
                assertTrue(C.isNetherCore(mid), "nether core at slot " + i);
                assertEquals(better, C.isBetterNetherAt(mid), "better nether at slot " + i);
            } else if (slot.type() == CycleLayout.Type.END) {
                ends++;
                assertEquals(better, C.isBetterEndAt(mid), "better end at slot " + i);
            }
        }
        assertEquals(2, nethers);
        assertEquals(2, ends);
    }
}
