package games.brennan.dungeontrain.worldgen.density;

import games.brennan.dungeontrain.worldgen.NetherMountainTerrain;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Correctness guarantee for the {@link NetherBandTerrainDensityFunction} per-column memo (the
 * performance optimisation): the memoised {@code raisedOrBase} must be <b>byte-identical</b> to a
 * direct, un-memoised recomputation for every {@code (x,z,y,base)}.
 *
 * <p>The grid crosses a nether band (raise ON) and the surrounding overworld (raise OFF / skip), with
 * many Y per column (exercising memo <em>hits</em>) and many columns (exercising misses + hash
 * collisions). A second block re-publishes a different-seed context to prove the memo's identity guard
 * drops stale columns rather than serving a wrong target. No NeoForge bootstrap — the pure
 * {@link WorldGenCycle}/{@link NetherMountainTerrain} math and a {@code null}-wrapped DF instance
 * (raisedOrBase never touches the wrapped density) are all that's needed.</p>
 */
final class NetherBandTerrainDensityFunctionTest {

    /** Must match {@code NetherBandTerrainDensityFunction.RAISE_SLOPE} (private) — asserting the formula too. */
    private static final double RAISE_SLOPE = 0.08;

    // Same geometry as WorldGenCycleTest: anchor 1000, owGap 300 → nether band world-X ≈ [1300, 1960).
    private static final WorldGenCycle CYCLE =
            new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 0, 0, 0, 0);

    @AfterEach
    void clearContext() {
        NetherBandContext.clear();
    }

    /** Un-memoised reference: the exact pre-optimisation logic of {@code raisedOrBase}. */
    private static double reference(WorldGenCycle cycle, long seed, int seaLevel, int ceiling,
                                    int netherTop, int baseRelief, int x, int z, int y, double base) {
        int wx = NetherMountainTerrain.wavyX(seed, x, z);
        if (!NetherMountainTerrain.raises(cycle, wx)) return base;
        double t = NetherMountainTerrain.targetTop(cycle, seed, wx, z, seaLevel, ceiling, netherTop, baseRelief);
        return Math.max(base, RAISE_SLOPE * (t - y));
    }

    @Test
    @DisplayName("memoised raisedOrBase is byte-identical to direct recompute across OW + band + edges")
    void memoIsByteIdentical() {
        long seed = 0x1234_5678L;
        int seaLevel = 63, ceiling = 320, netherTop = 40, baseRelief = 100;
        NetherBandContext ctx = new NetherBandContext(
                true, seed, seaLevel, ceiling, netherTop, baseRelief, CYCLE, null, null, null, null);
        NetherBandContext.publish(ctx);

        NetherBandTerrainDensityFunction df = new NetherBandTerrainDensityFunction(null);

        // x spans leading OW → nether band → trailing OW/End; z + y span whole columns (memo hits per column).
        for (int x = 1250; x <= 2000; x += 7) {
            for (int z = -16; z <= 16; z += 4) {
                for (int y = 40; y <= 250; y += 10) {
                    // base varied above/below the raise target so both max() branches are hit.
                    double base = ((x * 31 + z) * 7 + y) % 23 - 11;
                    double expected = reference(CYCLE, seed, seaLevel, ceiling, netherTop, baseRelief, x, z, y, base);
                    double actual = df.raisedOrBase(x, z, y, base);
                    assertEquals(expected, actual, 0.0,
                            "raisedOrBase mismatch at x=" + x + " z=" + z + " y=" + y + " base=" + base);
                }
            }
        }
    }

    @Test
    @DisplayName("republishing a different-seed context invalidates the memo (no stale target served)")
    void contextChangeInvalidatesMemo() {
        int seaLevel = 63, ceiling = 320, netherTop = 40, baseRelief = 100;
        long seedA = 0xAAAAL, seedB = 0xBBBBL;

        // Populate the memo under seed A across the band.
        NetherBandContext.publish(new NetherBandContext(
                true, seedA, seaLevel, ceiling, netherTop, baseRelief, CYCLE, null, null, null, null));
        NetherBandTerrainDensityFunction df = new NetherBandTerrainDensityFunction(null);
        for (int x = 1300; x <= 1900; x += 5) {
            for (int y = 60; y <= 200; y += 20) {
                df.raisedOrBase(x, 0, y, 0.0);
            }
        }

        // Same columns, different seed → the memo must recompute against seed B, not reuse seed A's target.
        NetherBandContext.publish(new NetherBandContext(
                true, seedB, seaLevel, ceiling, netherTop, baseRelief, CYCLE, null, null, null, null));
        for (int x = 1300; x <= 1900; x += 5) {
            for (int y = 60; y <= 200; y += 20) {
                double base = (x + y) % 7 - 3;
                double expected = reference(CYCLE, seedB, seaLevel, ceiling, netherTop, baseRelief, x, 0, y, base);
                double actual = df.raisedOrBase(x, 0, y, base);
                assertEquals(expected, actual, 0.0,
                        "stale-memo after context change at x=" + x + " y=" + y);
            }
        }
    }

    @Test
    @DisplayName("null / disabled context is a pure pass-through (returns base unchanged)")
    void disabledContextPassesThrough() {
        NetherBandContext.clear();
        NetherBandTerrainDensityFunction df = new NetherBandTerrainDensityFunction(null);
        assertEquals(7.5, df.raisedOrBase(1500, 0, 100, 7.5), 0.0);   // no context published

        NetherBandContext.publish(new NetherBandContext(
                false, 1L, 63, 320, 40, 100, CYCLE, null, null, null, null));   // enabled=false
        assertEquals(-3.25, df.raisedOrBase(1500, 0, 100, -3.25), 0.0);
    }
}
