package games.brennan.dungeontrain.worldgen.feature;

import games.brennan.dungeontrain.worldgen.NetherMountainTerrain;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Nether-core write guard for overworld decoration: its column test and its placement scope. */
final class OverworldDecorationGuardTest {

    /** Nether band on, End band off: owGap 100, 3 mountain stages of 40, coreFade 50, coreHold 200. */
    private static final WorldGenCycle NETHER_ONLY =
        new WorldGenCycle(0L, 100, 40, new int[] {1, 2, 4}, 0, 0, 50, 200, 0, 0, 0, 0, 0, 0, 0);

    /** Both bands present, so the End-wins branch is exercised. */
    private static final WorldGenCycle NETHER_AND_END =
        new WorldGenCycle(0L, 100, 40, new int[] {1, 2, 4}, 0, 0, 50, 200, 100, 40, 200, 0, 0, 0, 0);

    private static final long SEED = 0xC0FFEEL;

    @Test
    @DisplayName("core column = the stamp's test: core at the edge-waved X, End band wins")
    void matchesTheStampColumnTest() {
        for (WorldGenCycle cycle : new WorldGenCycle[] {NETHER_ONLY, NETHER_AND_END}) {
            for (int z = -64; z <= 64; z += 16) {
                for (int x = -50; x <= cycle.period() + 50; x++) {
                    int wx = NetherMountainTerrain.wavyX(SEED, x, z);
                    boolean expected = cycle.endMiddleRamp(wx) <= 0.0 && cycle.isNetherCore(wx);
                    assertEquals(expected, OverworldDecorationGuard.isCoreColumn(cycle, SEED, true, x, z),
                            "x=" + x + " z=" + z);
                }
            }
        }
    }

    @Test
    @DisplayName("core centre blocks, the overworld gap and the transition mountains don't")
    void coreVsTransition() {
        // Core centre: owGap(100) + rise(120) + coreFade(50) + coreHold/2(100) = 370 (see NetherBandCoreGateTest).
        assertTrue(OverworldDecorationGuard.isCoreColumn(NETHER_ONLY, SEED, false, 370, 0));
        assertFalse(OverworldDecorationGuard.isCoreColumn(NETHER_ONLY, SEED, false, 50, 0), "overworld gap");
        boolean sawTransition = false;
        for (int x = 101; x < 370; x++) {                // the rise mountains before the core
            if (NETHER_ONLY.netherRamp(NetherMountainTerrain.wavyX(SEED, x, 0)) > 0.0
                    && !NETHER_ONLY.isNetherCore(NetherMountainTerrain.wavyX(SEED, x, 0))) {
                sawTransition = true;
                assertFalse(OverworldDecorationGuard.isCoreColumn(NETHER_ONLY, SEED, false, x, 0), "transition x=" + x);
            }
        }
        assertTrue(sawTransition, "fixture has transition columns");
    }

    @Test
    @DisplayName("scope nests and closes; outside it nothing is refused")
    void scopeNestsAndCloses() {
        assertFalse(OverworldDecorationGuard.isActive());
        OverworldDecorationGuard.enter();
        OverworldDecorationGuard.enter();
        assertTrue(OverworldDecorationGuard.isActive());
        OverworldDecorationGuard.exit();
        assertTrue(OverworldDecorationGuard.isActive(), "still inside the outer feature");
        OverworldDecorationGuard.exit();
        assertFalse(OverworldDecorationGuard.isActive());
        OverworldDecorationGuard.exit();                 // unbalanced exit never goes negative
        assertFalse(OverworldDecorationGuard.isActive());
        assertFalse(OverworldDecorationGuard.blocksWrite(370, 0), "no chunk context, no scope: every write passes");
    }
}
