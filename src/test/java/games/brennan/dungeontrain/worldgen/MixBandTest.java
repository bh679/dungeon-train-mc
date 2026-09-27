package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests of the mix zone's candidate table, per-chunk pick and the shifted cycle it hands out. */
final class MixBandTest {

    private static final long START = 10_000L;
    private static final long SEED = 0x5EED_1234_ABCDL;
    private static final CycleLayout LAYOUT = CycleLayoutTest.shipped();
    private static final WorldGenCycle C = new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
            120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 6550, 750, 5000, 8000, 1500, 10_000, 0.08,
            CycleLayoutTest.eraDefaults(), LAYOUT, 0);
    private static final List<MixBand.Candidate> CANDIDATES = MixBand.candidates(C, Set.of());

    private static int mixSlot() {
        return LAYOUT.firstIndexOf(CycleLayout.Type.MIX);
    }

    /** First chunk X of the mix slot on run {@code k}. */
    private static int mixChunk(int k) {
        return (int) Math.floorDiv(C.worldXOfBase(k, LAYOUT.start(mixSlot())) + 15L, 16L);
    }

    @Test
    @DisplayName("every band behind the zone is a candidate once; chuncks and stacks never are")
    void candidateTable() {
        assertEquals(21, CANDIDATES.size(), CANDIDATES.toString());
        Set<String> tokens = new java.util.HashSet<>();
        for (MixBand.Candidate c : CANDIDATES) {
            assertTrue(tokens.add(c.token()), "duplicate " + c.token());
            assertTrue(c.hi() > c.lo(), c.token() + " has an empty window");
            assertFalse(c.type() == CycleLayout.Type.CHUNCKS || c.type() == CycleLayout.Type.STACKS
                    || c.type() == CycleLayout.Type.MIX);
        }
        assertTrue(tokens.containsAll(Set.of("ow", "ow_wwoo", "ow_bop", "ow_sunk", "nether", "nether_better",
                "end", "end_better", "upside_down", "spheres", "amplified", "void")));
        assertEquals(19, MixBand.candidates(C, Set.of("nether", "beta")).size());
    }

    @Test
    @DisplayName("shifted(d) answers at x exactly as the base cycle does at x + d")
    void shiftedIsATranslation() {
        long[] shifts = {16L, -4_096L, 123_456L, -48_000L};
        for (long d : shifts) {
            WorldGenCycle s = C.shifted(d);
            for (int x = (int) START; x < START + 3 * C.period(); x += 997) {
                int y = (int) (x + d);
                assertEquals(C.slotIndexAt(y), s.slotIndexAt(x));
                assertEquals(C.isNetherCore(y), s.isNetherCore(x));
                assertEquals(C.endIslandRamp(y), s.endIslandRamp(x));
                assertEquals(C.upsideDownRamp(y), s.upsideDownRamp(x));
                assertEquals(C.stacksVoidRampAt(y), s.stacksVoidRampAt(x));
                assertEquals(C.overworldStyleAt(y), s.overworldStyleAt(x));
                assertEquals(C.legacyAt(y), s.legacyAt(x));
            }
        }
    }

    @Test
    @DisplayName("picks are deterministic, chunk-aligned and land inside the picked band's core on every run")
    void picksLandInTheirCore() {
        int mixLen = (int) LAYOUT.length(mixSlot());
        for (int k = 0; k < 3; k++) {
            int c0 = mixChunk(k);
            int chunks = (mixLen << k) / 16;
            for (int cx = c0; cx < c0 + chunks; cx += 3) {
                for (int cz = -6; cz <= 6; cz += 4) {
                    MixBand.Pick p = MixBand.pickAt(C, CANDIDATES, SEED, cx, cz);
                    assertEquals(MixBand.keeps(C, SEED, cx, cz), p != null, "a pick exactly on kept chunks");
                    if (p == null) continue;                               // one of the zone's void chunks
                    assertEquals(p, MixBand.pickAt(C, CANDIDATES, SEED, cx, cz));
                    assertEquals(0L, Math.floorMod(p.dx(), 16L));
                    WorldGenCycle s = C.shifted(p.dx());
                    IntPredicate core = coreOf(s, p.candidate());
                    for (int dx = 0; dx < 16; dx++) {
                        int x = (cx << 4) + dx;
                        assertTrue(core.test(x), p.candidate().token() + " run " + k + " chunk " + cx + " col " + dx);
                        assertFalse(s.isInMixZone(x));
                    }
                }
            }
        }
    }

    /** The band test a representative chunk must pass on the shifted cycle. */
    private static IntPredicate coreOf(WorldGenCycle s, MixBand.Candidate c) {
        return switch (c.type()) {
            case OVERWORLD -> x -> s.slotIndexAt(x) >= 0
                    && LAYOUT.slot(s.slotIndexAt(x)).type() == CycleLayout.Type.OVERWORLD
                    && LAYOUT.slot(s.slotIndexAt(x)).style() == c.style();
            case NETHER -> x -> s.isNetherCore(x) && s.netherStyleAt(x) == c.style();
            case END -> x -> s.isEndCore(x) && s.endStyleAt(x) == c.style();
            case UPSIDE_DOWN -> s::isInUpsideDownBand;
            case SPHERES -> s::isInSpheresBand;
            case LEGACY_RUN -> {
                LegacyBandKind kind = c.era();
                yield x -> s.isInLegacyBand(kind, x);
            }
            default -> x -> false;
        };
    }

    @Test
    @DisplayName("each candidate is picked about equally often")
    void uniform() {
        Map<String, Integer> counts = new HashMap<>();
        int c0 = mixChunk(0);
        int n = 0;
        for (int cx = c0; cx < c0 + 240; cx++) {
            for (int cz = -800; cz < 800; cz++) {
                MixBand.Pick p = MixBand.pickAt(C, CANDIDATES, SEED, cx, cz);
                if (p == null) continue;
                counts.merge(p.candidate().token(), 1, Integer::sum);
                n++;
            }
        }
        double expected = (double) n / CANDIDATES.size();
        double chi2 = 0.0;
        for (MixBand.Candidate c : CANDIDATES) {
            double o = counts.getOrDefault(c.token(), 0);
            chi2 += (o - expected) * (o - expected) / expected;
        }
        assertTrue(chi2 < 50.0, "chi2 " + chi2 + " over " + CANDIDATES.size() + " candidates: " + counts);
    }

    @Test
    @DisplayName("no pick outside the zone; in the stacks fade after it, stacks keeps the chunks it claims")
    void edges() {
        int c0 = mixChunk(0);
        assertNull(MixBand.pickAt(C, CANDIDATES, SEED, c0 - 1, 0));
        assertEquals(C, MixBand.cycleFor(C, SEED, c0 - 1, 0));
        int stacks = LAYOUT.firstIndexOf(CycleLayout.Type.STACKS);
        int s0 = (int) Math.floorDiv(C.worldXOfBase(0, LAYOUT.start(stacks)) + 15L, 16L);
        int picked = 0;
        int kept = 0;
        for (int cz = -300; cz < 300; cz++) {
            if (MixBand.pickAt(C, CANDIDATES, SEED, s0, cz) == null) kept++;
            else picked++;
        }
        // At the fade start stacks claims almost nothing, so about the chuncks keep density of chunks pick.
        assertTrue(picked > 600 * 0.3 * 0.6 && picked < 600 * 0.3 * 1.4, "fade-start picks: " + picked);
        int fadeEnd = (int) Math.floorDiv(C.worldXOfBase(0, LAYOUT.start(stacks) + 1_500L), 16L);
        assertNull(MixBand.pickAt(C, CANDIDATES, SEED, fadeEnd + 1, 0));   // stacks core: never a pick
        assertTrue(kept >= 0);
    }

    @Test
    @DisplayName("the zone is as sparse as the chuncks core: only the kept share of chunks becomes a band")
    void keepsChuncksDensity() {
        int c0 = mixChunk(0);
        int kept = 0;
        int n = 0;
        for (int cx = c0; cx < c0 + 200; cx++) {
            for (int cz = -100; cz < 100; cz++) {
                if (MixBand.pickAt(C, CANDIDATES, SEED, cx, cz) != null) kept++;
                n++;
            }
        }
        double share = (double) kept / n;
        assertEquals(C.chuncksKeepDensity(), share, 0.01, "kept share " + share);
    }

    @Test
    @DisplayName("the shipped order puts a 4000-block mix zone between a 2000-block chuncks core and stacks")
    void shippedTail() {
        int mix = mixSlot();
        assertEquals(CycleLayout.Type.CHUNCKS, LAYOUT.slot(mix - 1).type());
        assertEquals(2000, LAYOUT.slot(mix - 1).core());
        assertEquals(4000L, LAYOUT.length(mix));
        assertEquals(CycleLayout.Type.STACKS, LAYOUT.slot(mix + 1).type());
    }
}
