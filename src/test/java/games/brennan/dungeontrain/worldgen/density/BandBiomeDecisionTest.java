package games.brennan.dungeontrain.worldgen.density;

import games.brennan.dungeontrain.worldgen.NetherMountainTerrain;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.density.BandBiomeDecision.Result;
import games.brennan.dungeontrain.worldgen.feature.CavernNoise;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link BandBiomeDecision#decide} — the pure per-quart forced-biome decision extracted from
 * {@code MultiNoiseBiomeSourceMixin} — against the pre-extraction mixin logic, reimplemented verbatim
 * below as the in-test reference. Dense (x, z, y) grids across a full cycle (band edges stride-1) on
 * all four provider-presence combinations, two seeds, so the added off-band early-out is proven
 * byte-identical: the exact same columns get the exact same {@code NETHER_CORE}/{@code END_CORE}/
 * {@code HIGHLAND}/{@code ORIGINAL} verdicts as before the optimisation.
 */
final class BandBiomeDecisionTest {

    // Same geometry as WorldGenCycleTest: anchor 1000, owGap 300 → nether [1300,1960), End [2260,2940).
    private static final WorldGenCycle CYCLE =
            new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 0, 0, 0, 0);

    /**
     * The pre-extraction mixin decision, verbatim: sea-level gate → waved Nether core → un-waved End
     * core → waved highland raise. No influence early-out — this is the ground truth the optimised
     * path must reproduce bit-for-bit.
     */
    private static Result reference(WorldGenCycle cycle, long seed, int seaLevel,
                                    boolean hasNetherCore, boolean hasEndCore,
                                    int blockX, int blockY, int blockZ) {
        if (blockY < seaLevel) return Result.ORIGINAL;
        int wx = NetherMountainTerrain.wavyX(seed, blockX, blockZ);
        if (hasNetherCore && cycle.isNetherCore(wx)) return Result.NETHER_CORE;
        if (hasEndCore && cycle.isEndCore(blockX)) return Result.END_CORE;
        if (!NetherMountainTerrain.raises(cycle, wx)) return Result.ORIGINAL;
        return Result.HIGHLAND;
    }

    private static final int CEILING = 320, NETHER_TOP = 40, BASE_RELIEF = 100;

    private static Result decide(WorldGenCycle cycle, long seed, int seaLevel, boolean hasNetherCore,
                                 boolean hasEndCore, int blockX, int blockY, int blockZ) {
        return BandBiomeDecision.decide(cycle, seed, seaLevel, hasNetherCore, hasEndCore, blockX, blockY, blockZ);
    }

    @Test
    @DisplayName("decide == pre-extraction mixin logic across a full cycle, all provider combos, two seeds")
    void matchesReferenceAcrossCycle() {
        int seaLevel = 63;
        boolean[] flags = {false, true};
        for (long seed : new long[] {0x1234_5678L, 0xDEAD_BEEFL}) {
            // Quart-aligned x/z (the mixin always passes blockX = quartX << 2); full period at stride 4,
            // y below/at/above sea level.
            for (int x = 960; x <= 2980; x += 4) {
                for (int z = -16; z <= 16; z += 8) {
                    for (int y : new int[] {40, 63, 80, 120, 250}) {
                        for (boolean hasNether : flags) {
                            for (boolean hasEnd : flags) {
                                assertEquals(
                                        reference(CYCLE, seed, seaLevel, hasNether, hasEnd, x, y, z),
                                        decide(CYCLE, seed, seaLevel, hasNether, hasEnd, x, y, z),
                                        "mismatch at x=" + x + " z=" + z + " y=" + y
                                                + " hasNether=" + hasNether + " hasEnd=" + hasEnd + " seed=" + seed);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("stride-1 band edges: the off-band early-out never flips a verdict at a boundary")
    void edgesStrideOne() {
        int seaLevel = 63;
        int margin = NetherMountainTerrain.maxEdgeShift();
        // Nether band edges 1300/1960, End segment edges 2260/2940 — each swept ±(margin+2) at stride 1.
        int[] edges = {1300, 1960, 2260, 2940};
        for (long seed : new long[] {0x1234_5678L, 0xDEAD_BEEFL}) {
            for (int edge : edges) {
                for (int x = edge - (margin + 2); x <= edge + (margin + 2); x++) {
                    for (int z = -16; z <= 16; z += 2) {
                        for (int y : new int[] {63, 150}) {
                            assertEquals(
                                    reference(CYCLE, seed, seaLevel, true, true, x, y, z),
                                    decide(CYCLE, seed, seaLevel, true, true, x, y, z),
                                    "edge mismatch at x=" + x + " z=" + z + " y=" + y + " seed=" + seed);
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("sanity: every verdict actually occurs on the grid (the test isn't vacuously ORIGINAL)")
    void allVerdictsReachable() {
        long seed = 0x1234_5678L;
        boolean sawNether = false, sawEnd = false, sawHighland = false, sawOriginal = false, sawCave = false;
        for (int x = 960; x <= 2980; x += 2) {
            switch (decide(CYCLE, seed, 63, true, true, x, 150, 0)) {
                case NETHER_CORE -> sawNether = true;
                case END_CORE -> sawEnd = true;
                case HIGHLAND -> sawHighland = true;
                case ORIGINAL -> sawOriginal = true;
            }
            int top = BandBiomeDecision.caveWindowTop(CYCLE, seed, 63, CEILING, NETHER_TOP, BASE_RELIEF, x, 0);
            if (BandBiomeDecision.isCave(top, 80)) sawCave = true;
        }
        assertTrue(sawCave, "no cave band on the sweep");
        assertTrue(sawNether, "no NETHER_CORE verdict on the sweep");
        assertTrue(sawEnd, "no END_CORE verdict on the sweep");
        assertTrue(sawHighland, "no HIGHLAND verdict on the sweep");
        assertTrue(sawOriginal, "no ORIGINAL verdict on the sweep");
    }

    @Test
    @DisplayName("A/B kill-switch OFF (baseline path) matches the reference too")
    void killSwitchOffMatches() {
        long seed = 0x1234_5678L;
        games.brennan.dungeontrain.worldgen.BandEarlyOuts.ENABLED = false;
        try {
            for (int x = 960; x <= 2980; x += 8) {
                assertEquals(reference(CYCLE, seed, 63, true, true, x, 150, 4),
                        decide(CYCLE, seed, 63, true, true, x, 150, 4),
                        "OFF-path mismatch at x=" + x);
            }
        } finally {
            games.brennan.dungeontrain.worldgen.BandEarlyOuts.ENABLED = true;
        }
    }

    @Test
    @DisplayName("cave band: only raising non-core columns have one, its top never exceeds the decoration ceiling")
    void caveBounds() {
        long seed = 0x1234_5678L;
        boolean any = false;
        for (int x = 1300; x < 1960; x += 4) {
            for (int z = -16; z <= 16; z += 8) {
                int top = BandBiomeDecision.caveWindowTop(CYCLE, seed, 63, CEILING, NETHER_TOP, BASE_RELIEF, x, z);
                int wx = NetherMountainTerrain.wavyX(seed, x, z);
                boolean highland = decide(CYCLE, seed, 63, true, true, x, 80, z) == Result.HIGHLAND;
                if (!highland) assertEquals(BandBiomeDecision.NO_CAVE, top, "cave band on a non-highland column at x=" + x);
                else { assertTrue(top <= CavernNoise.DECORATION_CEILING, "above the ceiling at x=" + x); any = true; }
                assertTrue(!BandBiomeDecision.isCave(top, CavernNoise.DECORATION_CEILING + 1));
                if (CYCLE.isNetherCore(wx)) assertEquals(BandBiomeDecision.NO_CAVE, top, "core column at x=" + x);
            }
        }
        assertTrue(any, "no highland column with a cave band");
    }

    @Test
    @DisplayName("null cycle → ORIGINAL (mixin's catch-all previously swallowed the NPE to original)")
    void nullCycleIsOriginal() {
        assertEquals(Result.ORIGINAL, decide(null, 1L, 63, true, true, 1500, 150, 0));
    }
}
