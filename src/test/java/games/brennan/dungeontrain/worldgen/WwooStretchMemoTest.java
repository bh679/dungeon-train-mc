package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacySpan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WwooStretchMemo#outside} must always equal the un-memoised
 * {@code SecondLapOverworld.lookAt(cycle, x) != WWOO} — whatever order X is asked in, whichever
 * cycle instance is live and whatever the reverse slide is. Layout as {@link BandTransitionLookTest}:
 * the upside-down Reassembly bleed, the {@code ow:wwoo} gap and the Nether's entry side wear WWOO, in
 * run 0 ahead of spawn, run 1 (doubled) and the reversed copy behind spawn.
 */
final class WwooStretchMemoTest {

    private static final long START = 10_000L;
    private static final CycleLayout.Fades FADES = new CycleLayout.Fades(232, 0, 300, 120, 500, 600, 600, 10_000, 1500, 1500, 1500, 480);
    private static final WorldGenCycle C = cycle(
            "ow:1000, upside_down:500:2000, ow:wwoo:3000, nether:better:3000, ow:bop:3000, end:better:3000, ow:1000");
    /** Same slots, different lengths — every WWOO edge moves. */
    private static final WorldGenCycle OTHER = cycle(
            "ow:1300, upside_down:500:2500, ow:wwoo:2000, nether:better:3000, ow:bop:3000, end:better:3000, ow:1000");

    /** Behind spawn (reversed runs 0–1) through run 1 ahead of it. */
    private static final int LO = (int) (START - 1_000 - 2 * C.period());
    private static final int HI = (int) (START + CycleLayout.runStart(2, C.period()));

    private static LegacySpan[] eraDefaults() {
        List<LegacySpan> out = new ArrayList<>();
        for (LegacyBandKind k : LegacyBandKind.values()) out.add(new LegacySpan(k, 3000, 480, 6000));
        return out.toArray(new LegacySpan[0]);
    }

    private static WorldGenCycle cycle(String order) {
        List<String> warnings = new ArrayList<>();
        CycleLayout layout = CycleLayout.parse(order, FADES, eraDefaults(), t -> true, warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        return new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
                120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 12_000, 1500, 5000, 8000, 1500, 10_000, 0.08,
                eraDefaults(), layout, 0);
    }

    @BeforeEach
    @AfterEach
    void reset() {
        WwooStretchMemo.clearThread();
        WorldGenCycle.setReverseSlide(0L);
    }

    private static void check(WorldGenCycle c, int x) {
        assertEquals(WwooStretchMemo.uncached(c, x), WwooStretchMemo.outside(c, x), "x=" + x);
    }

    @Test
    @DisplayName("the sweep crosses real WWOO edges: run 0, run 1 and behind spawn")
    void sweepHasEdges() {
        int edges = 0;
        int behind = 0;
        for (int x = LO + 1; x < HI; x++) {
            if (WwooStretchMemo.uncached(C, x) != WwooStretchMemo.uncached(C, x - 1)) {
                edges++;
                if (x < START) behind++;
            }
        }
        // Each run has one WWOO span (Reassembly bleed → gap → Nether entry side): an edge in, an edge out.
        // Reversed run 0 behind spawn + forward runs 0 and 1 → 6 edges, 2 of them behind.
        assertEquals(6, edges);
        assertEquals(2, behind);
    }

    @Test
    @DisplayName("forwards, backwards, slot-colliding strides, shuffled and repeated: always the uncached answer")
    void accessOrders() {
        for (int x = LO; x < HI; x++) check(C, x);
        for (int x = HI - 1; x >= LO; x--) check(C, x);           // warm hits on the tail, misses beyond
        for (int off = 0; off < 64; off++) {                      // every call lands on the same slot as the last
            for (int x = LO + off; x < HI; x += 64) check(C, x);
        }
        Random r = new Random(1234L);
        for (int i = 0; i < 200_000; i++) check(C, LO + r.nextInt(HI - LO));
        for (int rep = 0; rep < 3; rep++) {                       // blend-like: each X across the bleed start (u 3533), X±2
            for (int x = (int) START + 3_520; x < START + 3_550; x++) {
                for (int dx = -2; dx <= 2; dx++) check(C, x + dx);
            }
        }
    }

    @Test
    @DisplayName("a different cycle instance at the same X is never served the other's answer")
    void cycleIdentityInvalidates() {
        int differ = 0;
        for (int x = LO; x < HI; x++) {
            check(C, x);
            check(OTHER, x);
            check(C, x);
            if (WwooStretchMemo.uncached(C, x) != WwooStretchMemo.uncached(OTHER, x)) differ++;
        }
        assertTrue(differ > 0, "the two cycles must disagree somewhere for this test to mean anything");
    }

    @Test
    @DisplayName("a reverse-slide change behind spawn is seen immediately")
    void reverseSlideInvalidates() {
        int differ = 0;
        for (int x = LO; x < START; x++) {
            WorldGenCycle.setReverseSlide(0L);
            boolean before = WwooStretchMemo.outside(C, x);
            assertEquals(WwooStretchMemo.uncached(C, x), before, "x=" + x);
            WorldGenCycle.setReverseSlide(1_024L);
            boolean after = WwooStretchMemo.outside(C, x);
            assertEquals(WwooStretchMemo.uncached(C, x), after, "x=" + x + " slid");
            if (before != after) differ++;
        }
        assertTrue(differ > 0, "the slide must move a WWOO edge for this test to mean anything");
    }

    @Test
    @DisplayName("threads sweeping the same range each keep their own table and agree with the uncached answer")
    void threads() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                int seed = t;
                results.add(pool.submit(() -> {
                    Random r = new Random(seed);
                    int bad = 0;
                    for (int i = 0; i < 100_000; i++) {
                        int x = LO + r.nextInt(HI - LO);
                        if (WwooStretchMemo.outside(C, x) != WwooStretchMemo.uncached(C, x)) bad++;
                    }
                    return bad;
                }));
            }
            for (Future<Integer> f : results) assertEquals(0, f.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
