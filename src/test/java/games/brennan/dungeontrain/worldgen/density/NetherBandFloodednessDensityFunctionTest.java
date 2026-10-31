package games.brennan.dungeontrain.worldgen.density;

import games.brennan.dungeontrain.worldgen.NetherMountainTerrain;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The floodedness wrapper dries every raised (band mountain) column, at any Y, and nothing else. */
final class NetherBandFloodednessDensityFunctionTest {

    private static final WorldGenCycle CYCLE =
            new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 0, 0, 0, 0);

    /** Constant child so the pass-through value is recognisable. */
    private static final class Const implements DensityFunction {
        @Override public double compute(FunctionContext ctx) { return 0.9; }
        @Override public void fillArray(double[] v, ContextProvider cp) { java.util.Arrays.fill(v, 0.9); }
        @Override public DensityFunction mapAll(Visitor visitor) { return visitor.apply(this); }
        @Override public double minValue() { return 0.9; }
        @Override public double maxValue() { return 0.9; }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException();
        }
    }

    @AfterEach
    void clear() { NetherBandContext.clear(); }

    @Test
    @DisplayName("−1 in raised columns at every Y (sea floor to peak), child value off-band and with no context")
    void driesRaisedColumnsOnly() {
        long seed = 0x1234_5678L;
        NetherBandFloodednessDensityFunction df = new NetherBandFloodednessDensityFunction(new Const());
        assertEquals(0.9, df.compute(new DensityFunction.SinglePointContext(1500, 100, 0)), 0.0, "no context");
        NetherBandContext.publish(new NetherBandContext(true, seed, 63, 320, 40, 100, CYCLE, null, null, null, null, null, null, null));
        boolean sawDry = false, sawWet = false;
        for (int x = 1200; x <= 2000; x += 3) {
            for (int z = -12; z <= 12; z += 6) {
                boolean raised = NetherMountainTerrain.raises(CYCLE, NetherMountainTerrain.wavyX(seed, x, z));
                for (int y : new int[] {-40, 30, 63, 100, 250}) {
                    double v = df.compute(new DensityFunction.SinglePointContext(x, y, z));
                    assertEquals(raised ? -1.0 : 0.9, v, 0.0, "x=" + x + " z=" + z + " y=" + y);
                }
                if (raised) sawDry = true; else sawWet = true;
            }
        }
        assertTrue(sawDry && sawWet, "sweep did not cover both raised and off-band columns");
        assertTrue(df.minValue() <= -1.0);
    }

    @Test
    @DisplayName("fillArray matches compute per sample; mapAll re-wraps")
    void fillArrayAndMapAll() {
        long seed = 0xBEEFL;
        NetherBandContext.publish(new NetherBandContext(true, seed, 63, 320, 40, 100, CYCLE, null, null, null, null, null, null, null));
        NetherBandFloodednessDensityFunction df = new NetherBandFloodednessDensityFunction(new Const());
        int[] xs = new int[64];
        for (int i = 0; i < xs.length; i++) xs[i] = 1250 + i * 12;
        double[] values = new double[xs.length];
        df.fillArray(values, new DensityFunction.ContextProvider() {
            @Override public DensityFunction.FunctionContext forIndex(int i) {
                return new DensityFunction.SinglePointContext(xs[i], 90, 4);
            }
            @Override public void fillAllDirectly(double[] v, DensityFunction fn) { throw new UnsupportedOperationException(); }
        });
        for (int i = 0; i < xs.length; i++) {
            assertEquals(df.compute(new DensityFunction.SinglePointContext(xs[i], 90, 4)), values[i], 0.0, "i=" + i);
        }
        DensityFunction mapped = df.mapAll(f -> f);
        assertTrue(mapped instanceof NetherBandFloodednessDensityFunction, "mapAll must re-wrap");
    }

    @Test
    @DisplayName("the track's dry corridor spans every carriage width plus the margin, and nothing beyond")
    void trackDryCorridor() {
        int m = NetherBandFloodednessDensityFunction.TRACK_DRY_MARGIN;
        int maxZ = games.brennan.dungeontrain.train.CarriageDims.MAX_WIDTH - 1;
        assertTrue(m >= 32, "margin must cover the aquifer's cell-point reach");
        for (int z = -m; z <= maxZ + m; z++) {
            assertTrue(NetherBandFloodednessDensityFunction.inTrackDryCorridor(z), "z=" + z);
        }
        assertTrue(!NetherBandFloodednessDensityFunction.inTrackDryCorridor(-m - 1));
        assertTrue(!NetherBandFloodednessDensityFunction.inTrackDryCorridor(maxZ + m + 1));
    }

    @Test
    @DisplayName("away from the track, raised columns stay dry while no biome set is resolved")
    void offCorridorDryWithoutBiomeSet() {
        long seed = 0x1234_5678L;
        NetherBandContext.publish(new NetherBandContext(true, seed, 63, 320, 40, 100, CYCLE, null, null, null, null, null, null, null));
        NetherBandFloodednessDensityFunction df = new NetherBandFloodednessDensityFunction(new Const());
        boolean sawRaised = false;
        for (int x = 1200; x <= 2000; x += 7) {
            for (int z : new int[] {-300, -90, 120, 500}) {
                boolean raised = NetherMountainTerrain.raises(CYCLE, NetherMountainTerrain.wavyX(seed, x, z));
                assertEquals(raised ? -1.0 : 0.9, df.compute(new DensityFunction.SinglePointContext(x, 90, z)), 0.0,
                        "x=" + x + " z=" + z);
                sawRaised |= raised;
            }
        }
        assertTrue(sawRaised, "sweep did not cover a raised column");
    }
}
