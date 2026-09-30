package games.brennan.dungeontrain.worldgen.density;

import games.brennan.dungeontrain.worldgen.CycleLayout;
import games.brennan.dungeontrain.worldgen.SecondLapOverworld;
import games.brennan.dungeontrain.worldgen.ShippedCycles;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.density.BandBiomeDecision.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link ColumnBiomePlan}: every column served from the memo equals a fresh computation, the
 * providers are asked exactly once per column across vanilla's fill order, and a change to any
 * validation key (context, stretch tables, cycle, legacy token, reverse slide) misses.
 */
final class ColumnBiomePlanTest {

    /** Deterministic fake providers; {@code epoch} changes every answer, standing in for republished state. */
    private static final class Fake implements ColumnBiomePlan.Providers<String> {
        int epoch;
        int legacyCalls, decideCalls, netherCalls, endCalls, lookCalls;

        @Override public String legacy(int blockX, int blockZ) {
            legacyCalls++;
            return blockX >= 4000 && blockX < 4400 ? "legacy@" + blockX + "," + blockZ + "/" + epoch : null;
        }

        @Override public Result decideAboveSea(int blockX, int blockZ) {
            decideCalls++;
            if (blockX >= 1000 && blockX < 1400) return Result.HIGHLAND;
            if (blockX >= 1400 && blockX < 1800) return Result.NETHER_CORE;
            if (blockX >= 2200 && blockX < 2600) return Result.END_CORE;
            return Result.ORIGINAL;
        }

        @Override public int caveWindowTop(int blockX, int blockZ) {
            return blockX >= 1000 && blockX < 1400 ? 150 : BandBiomeDecision.NO_CAVE;
        }

        @Override public String netherCore(int blockX, int blockZ) {
            netherCalls++;
            return "nether@" + blockX + "," + blockZ + "/" + epoch;
        }

        @Override public String endCore(int blockX, int blockZ) {
            endCalls++;
            return "end@" + blockX + "," + blockZ + "/" + epoch;
        }

        @Override public SecondLapOverworld.Stretch look(int blockX) {
            lookCalls++;
            return blockX >= 3000 ? SecondLapOverworld.Stretch.BOP : SecondLapOverworld.Stretch.VANILLA;
        }
    }

    private static final Object CTX = new Object();
    private static final Object TABLES = new Object();
    private static final Object CYCLE = new Object();
    private static final Object LEGACY = new Object();

    private Fake fake;

    @BeforeEach
    void reset() {
        ColumnBiomePlan.clearThread();
        fake = new Fake();
    }

    private ColumnBiomePlan.Column<String> memo(int blockX, int blockZ) {
        return ColumnBiomePlan.column(blockX, blockZ, CTX, TABLES, CYCLE, LEGACY, 0L, fake);
    }

    private ColumnBiomePlan.Column<String> fresh(int blockX, int blockZ) {
        return ColumnBiomePlan.compute(blockX, blockZ, CTX, TABLES, CYCLE, LEGACY, 0L, new Fake());
    }

    @Test
    @DisplayName("vanilla fill order: every quart's column equals a fresh computation, providers asked once per column")
    void vanillaOrderHitsAfterFirstSection() {
        // Chunks spanning every verdict: ORIGINAL, HIGHLAND, NETHER_CORE, END_CORE, BoP look, legacy.
        int[] chunkXs = {0, 64, 92, 148, 190, 260};
        int columns = 0;
        for (int chunkX : chunkXs) {
            int chunkZ = -3;
            Set<Long> seen = new HashSet<>();
            for (int section = 0; section < 24; section++) {
                for (int qx = 0; qx < 4; qx++) {
                    for (int qy = 0; qy < 4; qy++) {
                        for (int qz = 0; qz < 4; qz++) {
                            int blockX = (chunkX * 4 + qx) << 2;
                            int blockZ = (chunkZ * 4 + qz) << 2;
                            ColumnBiomePlan.Column<String> got = memo(blockX, blockZ);
                            assertEquals(fresh(blockX, blockZ), got, "column " + blockX + "," + blockZ);
                            seen.add(((long) blockX << 32) | (blockZ & 0xFFFFFFFFL));
                        }
                    }
                }
            }
            assertEquals(16, seen.size());
            columns += 16;
        }
        assertEquals(columns, fake.legacyCalls, "legacy asked once per column");
        assertEquals(columns, fake.decideCalls + legacyColumns(chunkXs), "decide asked once per non-legacy column");
    }

    private static int legacyColumns(int[] chunkXs) {
        int n = 0;
        for (int chunkX : chunkXs) if (chunkX * 16 >= 4000 && chunkX * 16 < 4400) n += 16;
        return n;
    }

    @Test
    @DisplayName("random access order still answers every column exactly")
    void randomOrderIsExact() {
        Random rnd = new Random(0x5EED);
        for (int i = 0; i < 20_000; i++) {
            int blockX = (rnd.nextInt(1400) - 200) << 2;
            int blockZ = (rnd.nextInt(200) - 100) << 2;
            assertEquals(fresh(blockX, blockZ), memo(blockX, blockZ));
        }
    }

    @Test
    @DisplayName("core biomes are sampled once per column and reused for every quart of it")
    void coreSampledOncePerColumn() {
        for (int qy = 0; qy < 96; qy++) memo(1500, 8);
        assertEquals(1, fake.netherCalls);
        assertEquals("nether@1500,8/0", memo(1500, 8).core());
        for (int qy = 0; qy < 96; qy++) memo(2400, 8);
        assertEquals(1, fake.endCalls);
        assertEquals("end@2400,8/0", memo(2400, 8).core());
    }

    @Test
    @DisplayName("legacy columns short-circuit: no band decision, no look")
    void legacyShortCircuits() {
        ColumnBiomePlan.Column<String> c = memo(4100, 0);
        assertEquals("legacy@4100,0/0", c.legacy());
        assertEquals(Result.ORIGINAL, c.aboveSea());
        assertEquals(0, fake.decideCalls);
        assertEquals(0, fake.lookCalls);
    }

    @Test
    @DisplayName("a changed validation key misses and recomputes against the new state")
    void keyChangesInvalidate() {
        ColumnBiomePlan.Column<String> first = memo(1500, 8);
        assertSame(first, memo(1500, 8), "same keys → same record");
        fake.epoch = 1;                                        // state moved on…
        assertSame(first, memo(1500, 8), "…but unchanged keys keep serving the memo (keys must change too)");

        Object[][] keySets = {
                {new Object(), TABLES, CYCLE, LEGACY, 0L},
                {CTX, new Object(), CYCLE, LEGACY, 0L},
                {CTX, TABLES, new Object(), LEGACY, 0L},
                {CTX, TABLES, CYCLE, new Object(), 0L},
                {CTX, TABLES, CYCLE, LEGACY, 16L},
        };
        ColumnBiomePlan.Column<String> previous = first;
        for (Object[] keys : keySets) {
            fake.epoch++;
            int before = fake.netherCalls;
            ColumnBiomePlan.Column<String> got = ColumnBiomePlan.column(1500, 8,
                    keys[0], keys[1], keys[2], keys[3], (Long) keys[4], fake);
            assertNotSame(previous, got);
            assertEquals(before + 1, fake.netherCalls, "recomputed");
            assertEquals("nether@1500,8/" + fake.epoch, got.core());
            previous = got;
        }
    }

    /** Every column a Nether core; the core is the live provider's look, so the memo serves exactly what it would sample. */
    private static final class LiveNetherLook implements ColumnBiomePlan.Providers<CycleLayout.Style> {
        private final WorldGenCycle cycle;
        private final long seed;

        LiveNetherLook(WorldGenCycle cycle, long seed) {
            this.cycle = cycle;
            this.seed = seed;
        }

        @Override public CycleLayout.Style legacy(int blockX, int blockZ) { return null; }
        @Override public Result decideAboveSea(int blockX, int blockZ) { return Result.NETHER_CORE; }
        @Override public int caveWindowTop(int blockX, int blockZ) { return BandBiomeDecision.NO_CAVE; }
        @Override public CycleLayout.Style netherCore(int blockX, int blockZ) {
            return LiveColumnProviders.netherCoreLook(cycle, seed, blockX, blockZ);
        }
        @Override public CycleLayout.Style endCore(int blockX, int blockZ) { return null; }
        @Override public SecondLapOverworld.Stretch look(int blockX) { return SecondLapOverworld.Stretch.VANILLA; }
    }

    @Test
    @DisplayName("across the vanilla → BoP Nether mix the memoised core look equals the per-quart path's dithered look")
    void netherMixLookMatchesPerQuartPath() {
        WorldGenCycle cycle = ShippedCycles.CYCLE;
        long seed = 1450L;
        LiveNetherLook providers = new LiveNetherLook(cycle, seed);
        int mixStart = ShippedCycles.netherMixStartX();
        int mixEnd = mixStart + ShippedCycles.netherMixLength();
        int hardCutDisagreements = 0;
        for (int blockX = (mixStart - 64) & ~3; blockX < mixEnd + 64; blockX += 4) {
            for (int blockZ = -512; blockZ < 512; blockZ += 12) {
                // the per-quart path (MultiNoiseBiomeSourceMixin), surface skin, decoration and structures all ask this
                CycleLayout.Style perQuart = cycle.netherLookAt(blockX, blockZ, seed);
                CycleLayout.Style memo = ColumnBiomePlan.column(blockX, blockZ, CTX, TABLES, CYCLE, LEGACY, 0L, providers).core();
                assertEquals(perQuart, memo, "column " + blockX + "," + blockZ);
                if (cycle.netherLookAt(blockX) != perQuart) hardCutDisagreements++;
            }
        }
        // the sample really crosses the dither: the column-free hard cut would have mislabelled some of it
        assertTrue(hardCutDisagreements > 0, "no column in the mix differs from the hard cut");
    }

    @Test
    @DisplayName("the 16 columns of a chunk map to 16 distinct slots")
    void slotsAreDistinctWithinAChunk() {
        Set<Integer> slots = new HashSet<>();
        for (int qx = 0; qx < 4; qx++) {
            for (int qz = 0; qz < 4; qz++) slots.add(ColumnBiomePlan.slotOf((-7 * 4 + qx) << 2, (3 * 4 + qz) << 2));
        }
        assertEquals(16, slots.size());
    }
}
