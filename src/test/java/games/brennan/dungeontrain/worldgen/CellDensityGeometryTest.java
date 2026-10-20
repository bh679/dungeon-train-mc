package games.brennan.dungeontrain.worldgen;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CellDensityGeometry} with the Otherside's parameters (4×4 cells, y 0..127 on a bed at 64): exact
 * trilinear interpolation of the source's own cell corners, one evaluation per distinct corner, and the slab
 * mapping {@code OthersideGeometry} relies on. The Nether's parameters are covered by
 * {@link NetherCoreGeometryTest} through the delegate.
 */
final class CellDensityGeometryTest {

    private static final int CELL_W = 4;
    private static final int CELL_H = 4;
    private static final int CENTER_Y = OthersideCore.CENTER_Y;
    private static final int BED_Y = 64;

    private static class CountingDensity implements DensityFunction {
        final AtomicInteger calls = new AtomicInteger();

        static double f(int x, int y, int z) {
            return Math.cos(x * 0.09 + y * 0.05 + z * 0.17);
        }

        @Override
        public double compute(FunctionContext ctx) {
            calls.incrementAndGet();
            return f(ctx.blockX(), ctx.blockY(), ctx.blockZ());
        }

        @Override
        public void fillArray(double[] arr, ContextProvider provider) {
            provider.fillAllDirectly(arr, this);
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return this;
        }

        @Override
        public double minValue() {
            return -1;
        }

        @Override
        public double maxValue() {
            return 1;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException();
        }
    }

    private static CellDensityGeometry open(DensityFunction df) {
        return new CellDensityGeometry(df, CELL_W, CELL_H, CENTER_Y, OthersideCore.SAMPLE_Y_MIN, OthersideCore.SAMPLE_Y_MAX,
                BED_Y, -64, 319);
    }

    private static double expected(int x, int worldY, int z) {
        int sy = CENTER_Y + (worldY - BED_Y);
        int x0 = Math.floorDiv(x, CELL_W) * CELL_W;
        int z0 = Math.floorDiv(z, CELL_W) * CELL_W;
        int y0 = Math.floorDiv(sy, CELL_H) * CELL_H;
        double fx = (double) (x - x0) / CELL_W;
        double fz = (double) (z - z0) / CELL_W;
        double fy = (double) (sy - y0) / CELL_H;
        double bot = bilerp(CountingDensity.f(x0, y0, z0), CountingDensity.f(x0 + CELL_W, y0, z0),
                CountingDensity.f(x0, y0, z0 + CELL_W), CountingDensity.f(x0 + CELL_W, y0, z0 + CELL_W), fx, fz);
        double top = bilerp(CountingDensity.f(x0, y0 + CELL_H, z0), CountingDensity.f(x0 + CELL_W, y0 + CELL_H, z0),
                CountingDensity.f(x0, y0 + CELL_H, z0 + CELL_W), CountingDensity.f(x0 + CELL_W, y0 + CELL_H, z0 + CELL_W), fx, fz);
        return bot + (top - bot) * fy;
    }

    private static double bilerp(double d00, double d10, double d01, double d11, double fx, double fz) {
        double a = d00 + (d10 - d00) * fx;
        double b = d01 + (d11 - d01) * fx;
        return a + (b - a) * fz;
    }

    @Test
    @DisplayName("the slab spans Otherside y 0..127 around the bed")
    void slabMapping() {
        CellDensityGeometry geom = open(new CountingDensity());
        assertTrue(geom.isUsable());
        assertEquals(OthersideCore.slabBottom(BED_Y), geom.minY());
        assertEquals(OthersideCore.slabTop(BED_Y), geom.maxY());
        assertEquals(CENTER_Y, geom.spaceY(BED_Y));
        assertEquals(BED_Y, geom.worldY(CENTER_Y));
        assertEquals(0, geom.spaceY(geom.minY()));
        assertEquals(127, geom.spaceY(geom.maxY()));
    }

    @Test
    @DisplayName("every block of the slab is the trilinear interpolation of its cell corners")
    void exactInterpolation() {
        CellDensityGeometry geom = open(new CountingDensity());
        for (int x = -9; x <= 9; x += 3) {
            for (int z = -5; z <= 7; z += 2) {
                CellDensityGeometry.Column column = geom.column(x, z);
                for (int y = geom.minY(); y <= geom.maxY(); y++) {
                    assertTrue(geom.isSampled(y), "y " + y);
                    assertEquals(expected(x, y, z), column.densityAt(y), 1e-12, "(" + x + "," + y + "," + z + ")");
                }
                assertTrue(Double.isNaN(column.densityAt(geom.minY() - 1)));
                assertTrue(Double.isNaN(column.densityAt(geom.maxY() + 1)));
                assertFalse(column.isSolid(geom.maxY() + 1));
            }
        }
    }

    @Test
    @DisplayName("a 16×16 chunk evaluates each of its 5×5 corner columns once: 25 × 33 rows")
    void cornerMemo() {
        CountingDensity df = new CountingDensity();
        CellDensityGeometry geom = open(df);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                CellDensityGeometry.Column column = geom.column(x, z);
                for (int y = geom.minY(); y <= geom.maxY(); y++) column.densityAt(y);
            }
        }
        // rows = floorDiv(127, 4) - 0 + 2 = 33 cell-Y boundaries per corner column
        assertEquals(25 * 33, df.calls.get());
    }
}
