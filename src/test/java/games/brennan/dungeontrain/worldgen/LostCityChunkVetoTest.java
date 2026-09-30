package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.LostCityChunkVeto.Verdict;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LostCityChunkVetoTest {

    private static final long SEED = 1450L;
    private static final WorldGenCycle C = LostCityStructuresTest.C;
    private static final CycleLayout LAYOUT = LostCityStructuresTest.LAYOUT;

    @AfterEach
    void restore() {
        LostCityChunkVeto.ENABLED = true;
    }

    /** Chunk X of base coordinate {@code u} on lap 0. */
    private static int cx(long u) {
        return LostCityStructuresTest.x(u, 0) >> 4;
    }

    /** From the Nether before the lead-in, across the run-in, the density fade and the core, past the exit margin. */
    private static int[] eraSweep() {
        long start = LostCityStructuresTest.legacyStart() - LAYOUT.legacyLeadIn(LostCityStructuresTest.slot()) - 500L;
        long end = LostCityStructuresTest.coreStart() + C.legacyLen(LegacyBandKind.LOST_CITY) + 500L;
        return new int[] {cx(start), cx(end)};
    }

    /** Lap 1's WWOO stretch (slot 2), end to end with a margin either side. */
    private static int[] wwooSweep() {
        return new int[] {cx(LAYOUT.start(2) - 200L), cx(LAYOUT.start(2) + LAYOUT.length(2) + 200L)};
    }

    private static void assertSweep(int[] range, int zFrom, int zTo, boolean reverse, int repeats, Set<Verdict> seen) {
        for (int n = 0; n <= range[1] - range[0]; n++) {
            int x = reverse ? range[1] - n : range[0] + n;
            for (int z = zFrom; z < zTo; z++) {
                Verdict direct = LostCityChunkVeto.compute(SEED, C, x, z);
                // the direct verdict is exactly the rule the veto applied before the memo
                boolean allowed = LostCityStructures.allowedAt(SEED, C, x, z);
                assertEquals(!allowed, direct == Verdict.DENY, "x=" + x + " z=" + z);
                if (allowed) {
                    assertEquals(LostCityStructures.inWwooStretch(C, x), direct == Verdict.ALLOW_IF_WWOO_BUILDING);
                }
                for (int r = 0; r < repeats; r++) {
                    assertEquals(direct, LostCityChunkVeto.verdict(SEED, C, x, z), "x=" + x + " z=" + z + " r=" + r);
                }
                seen.add(direct);
            }
        }
    }

    @Test
    @DisplayName("the memoised verdict equals the direct one across the era boundary and the WWOO stretch")
    void memoMatchesDirect() {
        Set<Verdict> seen = EnumSet.noneOf(Verdict.class);
        assertSweep(eraSweep(), -8, 8, false, 1, seen);
        assertSweep(eraSweep(), -8, 8, true, 1, seen);
        assertSweep(wwooSweep(), -40, 40, false, 1, seen);
        assertEquals(EnumSet.allOf(Verdict.class), seen, "the sweep crosses every verdict");
    }

    @Test
    @DisplayName("a set's worth of retries in one chunk keeps the direct verdict")
    void retriesMatchDirect() {
        Set<Verdict> seen = EnumSet.noneOf(Verdict.class);
        int[] era = eraSweep();
        for (int x = era[0]; x <= era[1]; x += 3) {
            assertSweep(new int[] {x, x}, 0, 6, false, 71, seen);
        }
        assertTrue(seen.contains(Verdict.DENY) && seen.contains(Verdict.ALLOW), seen.toString());
    }

    @Test
    @DisplayName("a colliding slot, another seed or another cycle never serves a stale verdict")
    void keyedOnEveryInput() {
        int[] era = eraSweep();
        WorldGenCycle other = LostCityStructuresTest.cycle(LAYOUT);   // equal layout, different instance
        for (int x = era[0]; x <= era[1]; x += 5) {
            for (int z = -4; z < 4; z++) {
                for (long seed : new long[] {SEED, SEED + 1, -7L}) {
                    assertEquals(LostCityChunkVeto.compute(seed, C, x, z), LostCityChunkVeto.verdict(seed, C, x, z));
                    assertEquals(LostCityChunkVeto.compute(seed, other, x, z), LostCityChunkVeto.verdict(seed, other, x, z));
                }
                // same slot ((x*31 + z) & 63), different chunk
                assertEquals(LostCityChunkVeto.compute(SEED, C, x, z + 64), LostCityChunkVeto.verdict(SEED, C, x, z + 64));
                assertEquals(LostCityChunkVeto.compute(SEED, C, x, z), LostCityChunkVeto.verdict(SEED, C, x, z));
            }
        }
    }

    @Test
    @DisplayName("OFF is the direct path")
    void disabledIsDirect() {
        LostCityChunkVeto.ENABLED = false;
        assertSweep(eraSweep(), -2, 2, false, 2, EnumSet.noneOf(Verdict.class));
    }
}
