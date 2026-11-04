package games.brennan.dungeontrain.worldgen.feature;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins which chunks the End band's feature fallback considers — the ones vanilla's decoration loop can leave
 * bare now that End-band columns are End-labelled at every height (#2469): any chunk holding an End-void
 * column. Dropping the fallback generated the whole vanilla End piece as void (v0.1649.0 / v0.1650.0).
 */
final class EndBandFeatureFallbackTest {

    // Same geometry as WorldGenCycleTest: anchor 1000, owGap 300 → nether [1300,1960), End [2260,2940).
    private static final WorldGenCycle CYCLE =
            new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 0, 0, 0, 0);

    @Test
    @DisplayName("a chunk touches the End void iff one of its columns is End-labelled at every height")
    void matchesColumnRule() {
        boolean sawTouching = false;
        boolean sawClear = false;
        for (int chunkMinX = 960; chunkMinX <= 3008; chunkMinX += 16) {
            boolean expected = false;
            for (int dx = 0; dx < 16; dx++) {
                int x = chunkMinX + dx;
                if (CYCLE.endSegmentInfluence(x) && CYCLE.isEndVoid(x)) expected = true;
            }
            assertEquals(expected, EndBandFeatureFallback.touchesEndVoid(CYCLE, chunkMinX), "chunk at x=" + chunkMinX);
            if (expected) sawTouching = true; else sawClear = true;
        }
        assertTrue(sawTouching, "no End-void chunk on the sweep");
        assertTrue(sawClear, "no clear chunk on the sweep");
    }

    @Test
    @DisplayName("the island core and the void holds either side of it are covered; the overworld and Nether are not")
    void coversCoreAndVoidHolds() {
        int core = -1;
        for (int x = 2260; x < 2940; x++) if (CYCLE.isEndCore(x)) { core = x; break; }
        assertTrue(core > 0, "no End core column in the fixture");
        assertTrue(EndBandFeatureFallback.touchesEndVoid(CYCLE, core & ~15), "island core");
        int hold = -1;
        for (int x = 2260; x < core; x++) if (CYCLE.isEndVoid(x) && !CYCLE.isEndCore(x)) { hold = x; break; }
        assertTrue(hold > 0, "no leading void-hold column in the fixture");
        assertTrue(EndBandFeatureFallback.touchesEndVoid(CYCLE, hold & ~15), "leading void hold");
        assertFalse(EndBandFeatureFallback.touchesEndVoid(CYCLE, 1008), "plain overworld");
        assertFalse(EndBandFeatureFallback.touchesEndVoid(CYCLE, 1600), "Nether band");
        assertFalse(EndBandFeatureFallback.touchesEndVoid(null, 2600), "no cycle");
    }
}
