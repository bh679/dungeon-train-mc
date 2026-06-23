package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure terrain-noise mountain math {@link NetherMountainTerrain} — the target
 * top + the band gate the density wrapper turns into a {@code max(child, k·(T − y))} raise.
 */
final class NetherMountainTerrainTest {

    // owGap 0 → band starts at X=0. beach 20, 5 stages × 40, megaHold 0, coreFade 50, coreHold 100, End off.
    private static final WorldGenCycle C =
            new WorldGenCycle(0L, 0, 40, new int[] {1, 2, 4, 8, 15}, 20, 0, 50, 100, 0, 0, 0, 0);

    private static final long SEED = 0x123456789ABCDEFL;
    private static final int SEA = 63;
    private static final int CEILING = 319;
    private static final int NETHER_TOP = 100;
    private static final int BASE_RELIEF = 16;
    private static final double EPS = 1e-6;

    private static double top(int x, int z) {
        return NetherMountainTerrain.targetTop(C, SEED, x, z, SEA, CEILING, NETHER_TOP, BASE_RELIEF);
    }

    @Test
    @DisplayName("raises() gates: off before the band, off in the beach span, on in the mountain stages")
    void gating() {
        assertFalse(NetherMountainTerrain.raises(C, -5), "before the anchor");
        assertFalse(NetherMountainTerrain.raises(C, 0), "exact band start (heightRamp == 0)");
        assertFalse(NetherMountainTerrain.raises(C, 10), "inside the leading beach span");
        assertTrue(NetherMountainTerrain.raises(C, 30), "stage 1 mountain");
        assertTrue(NetherMountainTerrain.raises(C, 200), "late stage");
        assertTrue(NetherMountainTerrain.raises(C, 300), "core region (still raised, post-process replaces it)");
    }

    @Test
    @DisplayName("the End band wins: no raise where endMiddleRamp > 0")
    void endPrecedence() {
        // A cycle whose End segment overlaps the X we probe: give it an End band and check a column it owns.
        WorldGenCycle withEnd = new WorldGenCycle(0L, 0, 40, new int[] {1, 2}, 0, 0, 0, 0, 60, 40, 200, 0);
        // Find any X the End middle ramp owns and assert the mountain yields there.
        boolean foundEndColumn = false;
        for (int x = 0; x < (int) withEnd.period(); x++) {
            if (withEnd.endMiddleRamp(x) > 0.0) {
                foundEndColumn = true;
                assertFalse(NetherMountainTerrain.raises(withEnd, x), "mountain must yield to the End at x=" + x);
            }
        }
        assertTrue(foundEndColumn, "test fixture should contain an End-owned column");
    }

    @Test
    @DisplayName("target top stays within [seaLevel, worldCeiling] everywhere in the band")
    void boundsHold() {
        for (int x = 0; x < (int) C.netherLen(); x++) {
            for (int z = -40; z <= 40; z += 17) {
                double t = top(x, z);
                assertTrue(t >= SEA && t <= CEILING, "top out of range at x=" + x + " z=" + z + " -> " + t);
            }
        }
    }

    @Test
    @DisplayName("the mountain tapers to exactly the nether-core height across the real-Nether core")
    void tapersToNetherTopInCore() {
        // Core (netherRamp == 1) is offset [270, 370) for this fixture; taper makes top == netherTop there.
        assertEquals(1.0, C.netherRamp(300), EPS, "fixture: X=300 should be the core");
        for (int z = -50; z <= 50; z += 13) {
            assertEquals(NETHER_TOP, top(300, z), EPS, "core column should sit at netherTop regardless of z");
        }
    }

    @Test
    @DisplayName("in the mountain stages the top carries real relief (varies with position, never flat)")
    void reliefVariesInStages() {
        double first = top(120, 0);
        boolean varied = false;
        for (int z = 1; z <= 200; z++) {
            if (Math.abs(top(120, z) - first) > EPS) { varied = true; break; }
        }
        assertTrue(varied, "stage column should show MountainNoise relief across Z");
    }

    @Test
    @DisplayName("target top is deterministic for a given seed + position")
    void deterministic() {
        assertEquals(top(137, 42), top(137, 42), 0.0);
        // A different seed gives (almost surely) a different top at a relief-bearing column.
        double a = NetherMountainTerrain.targetTop(C, SEED, 137, 42, SEA, CEILING, NETHER_TOP, BASE_RELIEF);
        double b = NetherMountainTerrain.targetTop(C, SEED ^ 0xFFFF, 137, 42, SEA, CEILING, NETHER_TOP, BASE_RELIEF);
        assertTrue(Math.abs(a - b) > EPS, "distinct seeds should shape distinct mountains");
    }

    @Test
    @DisplayName("the ceiling clamp caps mega-stage columns at worldCeiling")
    void ceilingClamp() {
        int lowCeiling = 80;
        boolean clampHit = false;
        // Late stage-5 columns (high multiplier, n == 0) exceed a low ceiling without the clamp.
        for (int z = 0; z <= 500; z++) {
            double t = NetherMountainTerrain.targetTop(C, SEED, 219, z, SEA, lowCeiling, NETHER_TOP, BASE_RELIEF);
            assertTrue(t <= lowCeiling, "top must never exceed worldCeiling, got " + t + " at z=" + z);
            if (Math.abs(t - lowCeiling) < EPS) clampHit = true;
        }
        assertTrue(clampHit, "a high-relief mega column should clamp to worldCeiling");
    }
}
