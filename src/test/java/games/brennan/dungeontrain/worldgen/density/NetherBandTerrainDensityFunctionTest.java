package games.brennan.dungeontrain.worldgen.density;

import games.brennan.dungeontrain.worldgen.NetherMountainTerrain;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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

    @Test
    @DisplayName("early-out predicate: byte-identical across the WHOLE period + stride-1 band edges, multiple z and seeds")
    void earlyOutIsByteIdenticalAcrossPeriodAndEdges() {
        // Guards the netherInfluence fast path added to raise()/fillArray: a non-conservative window
        // would seam terrain exactly at a band edge, so the edges get stride-1 coverage over
        // ±(maxEdgeShift + 2) while the rest of the period is swept coarser. Two seeds so the edge
        // wave (seed-dependent) can't accidentally align with the window on one lucky seed.
        int seaLevel = 63, ceiling = 320, netherTop = 40, baseRelief = 100;
        int margin = NetherMountainTerrain.maxEdgeShift();
        long period = CYCLE.period();                                   // 1940 for this geometry
        long anchor = 1000L;
        // Nether band world-X [1300, 1960): stride-1 windows around both edges, ±(margin+2).
        int[][] denseEdges = {
                {1300 - (margin + 2), 1300 + (margin + 2)},
                {1960 - (margin + 2), 1960 + (margin + 2)},
        };
        for (long seed : new long[] {0x1234_5678L, 0xDEAD_BEEFL}) {
            NetherBandContext.publish(new NetherBandContext(
                    true, seed, seaLevel, ceiling, netherTop, baseRelief, CYCLE, null, null, null, null));
            NetherBandTerrainDensityFunction df = new NetherBandTerrainDensityFunction(null);
            // Whole-period sweep (coarse stride, prime so it doesn't sync with chunk/quart alignment).
            for (long lx = anchor - 40; lx <= anchor + period + 40; lx += 3) {
                int x = (int) lx;
                for (int z = -12; z <= 12; z += 6) {
                    for (int y = 50; y <= 240; y += 38) {
                        double base = ((x * 31 + z) * 7 + y) % 23 - 11;
                        assertEquals(
                                reference(CYCLE, seed, seaLevel, ceiling, netherTop, baseRelief, x, z, y, base),
                                df.raisedOrBase(x, z, y, base), 0.0,
                                "period sweep mismatch at x=" + x + " z=" + z + " y=" + y + " seed=" + seed);
                    }
                }
            }
            // Stride-1 edge windows — the exact stretch where a bad predicate would seam.
            for (int[] edge : denseEdges) {
                for (int x = edge[0]; x <= edge[1]; x++) {
                    for (int z = -16; z <= 16; z += 4) {
                        for (int y = 40; y <= 250; y += 10) {
                            double base = ((x * 31 + z) * 7 + y) % 23 - 11;
                            assertEquals(
                                    reference(CYCLE, seed, seaLevel, ceiling, netherTop, baseRelief, x, z, y, base),
                                    df.raisedOrBase(x, z, y, base), 0.0,
                                    "edge mismatch at x=" + x + " z=" + z + " y=" + y + " seed=" + seed);
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("A/B kill-switch OFF (baseline paths) is byte-identical too — the toggle changes timing only")
    void killSwitchOffIsByteIdentical() {
        long seed = 0x1234_5678L;
        int seaLevel = 63, ceiling = 320, netherTop = 40, baseRelief = 100;
        NetherBandContext.publish(new NetherBandContext(
                true, seed, seaLevel, ceiling, netherTop, baseRelief, CYCLE, null, null, null, null));
        NetherBandTerrainDensityFunction df = new NetherBandTerrainDensityFunction(null);
        games.brennan.dungeontrain.worldgen.BandEarlyOuts.ENABLED = false;
        try {
            for (int x = 1250; x <= 2000; x += 11) {
                for (int y = 50; y <= 240; y += 30) {
                    double base = (x * 7 + y) % 19 - 9;
                    assertEquals(reference(CYCLE, seed, seaLevel, ceiling, netherTop, baseRelief, x, 3, y, base),
                            df.raisedOrBase(x, 3, y, base), 0.0, "OFF-path mismatch at x=" + x + " y=" + y);
                }
            }
        } finally {
            games.brennan.dungeontrain.worldgen.BandEarlyOuts.ENABLED = true;
        }
    }

    // ---- fillArray path (the batch-invariant-hoisting refactor) ----------------------------------

    /** Deterministic per-sample child base, varied above/below the raise target so both {@code max} branches fire. */
    private static double childBase(int x, int y, int z) {
        return ((x * 31 + z) * 7 + y) % 23 - 11;
    }

    /**
     * A {@link DensityFunction.ContextProvider} + child density that replay the real full-cell fill order
     * ({@link net.minecraft.world.level.levelgen.NoiseChunk#fillAllDirectly}: Y-outer, X-mid, Z-inner) so the
     * refactored {@code fillArray} is exercised exactly as chunk gen drives it. The wrapped child fills each
     * slot with {@link #childBase}; nothing else on it is touched by {@code fillArray}.
     */
    private record Sample(int x, int y, int z) {}

    private static final class CellProvider implements DensityFunction.ContextProvider {
        final List<Sample> samples;
        CellProvider(List<Sample> samples) { this.samples = samples; }
        @Override public DensityFunction.FunctionContext forIndex(int i) {
            Sample s = samples.get(i);
            return new DensityFunction.SinglePointContext(s.x(), s.y(), s.z());
        }
        @Override public void fillAllDirectly(double[] values, DensityFunction fn) {
            throw new UnsupportedOperationException("not exercised by NetherBandTerrainDensityFunction.fillArray");
        }
    }

    /** Child that fills {@code values} with {@link #childBase} at each sample's coords — stands in for the router. */
    private static final class BaseChild implements DensityFunction {
        final List<Sample> samples;
        BaseChild(List<Sample> samples) { this.samples = samples; }
        @Override public void fillArray(double[] values, ContextProvider cp) {
            for (int i = 0; i < values.length; i++) {
                Sample s = samples.get(i);
                values[i] = childBase(s.x(), s.y(), s.z());
            }
        }
        @Override public double compute(FunctionContext ctx) { throw new UnsupportedOperationException(); }
        @Override public DensityFunction mapAll(Visitor v) { throw new UnsupportedOperationException(); }
        @Override public double minValue() { return Double.NEGATIVE_INFINITY; }
        @Override public double maxValue() { return Double.POSITIVE_INFINITY; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    @DisplayName("fillArray (invariant-hoisted loop) is byte-identical to per-sample reference across OW + band")
    void fillArrayIsByteIdentical() {
        long seed = 0x1234_5678L;
        int seaLevel = 63, ceiling = 320, netherTop = 40, baseRelief = 100;
        NetherBandContext.publish(new NetherBandContext(
                true, seed, seaLevel, ceiling, netherTop, baseRelief, CYCLE, null, null, null, null));

        // One fillArray call PER cell (matching the real caller: a NoiseChunk batch never spans more
        // than one chunk), each 4×4 columns × several Y layers in Y-outer/X-mid/Z-inner order. Origins
        // span leading OW → nether band → trailing OW: 1100/2400 are deep off-band (the whole-batch
        // O(1) reject fires — child values must come through untouched), 1264/1296/1952 sit near or on
        // an edge (reject must NOT fire; per-X mix of skip and raise inside one cell), 1600/1900 are
        // fully in-band.
        int[] cellOriginX = {1100, 1264, 1296, 1328, 1600, 1900, 1952, 2400};
        for (int ox : cellOriginX) {
            List<Sample> samples = new ArrayList<>();
            for (int y = 200; y >= 40; y -= 8) {                     // Y-outer, descending (matches fillAllDirectly)
                for (int dx = 0; dx < 4; dx++) {                     // X-mid
                    for (int dz = 0; dz < 4; dz++) {                 // Z-inner
                        samples.add(new Sample(ox + dx, y, -8 + dz));
                    }
                }
            }
            NetherBandTerrainDensityFunction df = new NetherBandTerrainDensityFunction(new BaseChild(samples));
            double[] values = new double[samples.size()];
            df.fillArray(values, new CellProvider(samples));

            for (int i = 0; i < samples.size(); i++) {
                Sample s = samples.get(i);
                double expected = reference(CYCLE, seed, seaLevel, ceiling, netherTop, baseRelief,
                        s.x(), s.z(), s.y(), childBase(s.x(), s.y(), s.z()));
                assertEquals(expected, values[i], 0.0,
                        "fillArray mismatch at ox=" + ox + " x=" + s.x() + " z=" + s.z() + " y=" + s.y());
            }
        }
    }

    @Test
    @DisplayName("fillArray with null / disabled context leaves the child output untouched")
    void fillArrayPassThroughWhenDisabled() {
        List<Sample> samples = new ArrayList<>();
        for (int y = 100; y >= 60; y -= 8) {
            for (int dx = 0; dx < 4; dx++) {
                for (int dz = 0; dz < 4; dz++) {
                    samples.add(new Sample(1500 + dx, y, dz));      // in-band X, but context disabled
                }
            }
        }
        NetherBandTerrainDensityFunction df = new NetherBandTerrainDensityFunction(new BaseChild(samples));

        // No context published → pure pass-through.
        double[] values = new double[samples.size()];
        df.fillArray(values, new CellProvider(samples));
        for (int i = 0; i < samples.size(); i++) {
            Sample s = samples.get(i);
            assertEquals(childBase(s.x(), s.y(), s.z()), values[i], 0.0, "expected untouched child base at i=" + i);
        }

        // enabled=false → also pass-through.
        NetherBandContext.publish(new NetherBandContext(
                false, 1L, 63, 320, 40, 100, CYCLE, null, null, null, null));
        double[] values2 = new double[samples.size()];
        df.fillArray(values2, new CellProvider(samples));
        for (int i = 0; i < samples.size(); i++) {
            Sample s = samples.get(i);
            assertEquals(childBase(s.x(), s.y(), s.z()), values2[i], 0.0, "expected untouched child base at i=" + i);
        }
    }
}
