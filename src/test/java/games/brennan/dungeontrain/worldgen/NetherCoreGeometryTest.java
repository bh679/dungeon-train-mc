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
 * Correctness + dedup guarantee for {@link NetherCoreGeometry} — the shared Nether-core density sampler
 * behind both the core fill ({@code NetherTransitionFeature}) and the band's Nether structures.
 *
 * <p>Two things must hold. It has to reproduce the <b>trilinear interpolation of the Nether's own
 * noise-cell corners</b> exactly (the fill and a fortress's ground probe disagreeing by a block would put
 * the fortress in the wrong place), and it has to evaluate each distinct corner <b>exactly once</b> — the
 * corner memo is what cuts the confirmed Nether-crossing bottleneck (12–33 ms/chunk of real-Nether router
 * sampling). Driven with a deterministic counting stub {@link DensityFunction}, so no NeoForge bootstrap
 * is needed (only {@code compute(SinglePointContext)} is exercised).</p>
 */
final class NetherCoreGeometryTest {

    private static final int CELL_W = 4;
    private static final int CELL_H = 8;
    private static final int NETHER_SEA_LEVEL = 32;
    private static final int BED_Y = 64;
    private static final int MIN_Y = -64;
    private static final int MAX_Y = 319;

    /** Deterministic stub Nether router: {@code compute} is a pure function of (x,y,z), counting calls. */
    private static class CountingDensity implements DensityFunction {
        final AtomicInteger calls = new AtomicInteger();

        static double f(int x, int y, int z) {
            return Math.sin(x * 0.11 + y * 0.07 + z * 0.13);
        }

        @Override public double compute(FunctionContext c) {
            calls.incrementAndGet();
            return f(c.blockX(), c.blockY(), c.blockZ());
        }
        @Override public void fillArray(double[] values, ContextProvider provider) { throw new UnsupportedOperationException(); }
        @Override public DensityFunction mapAll(Visitor visitor) { return this; }
        @Override public double minValue() { return -1.0; }
        @Override public double maxValue() { return 1.0; }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { throw new UnsupportedOperationException(); }
    }

    private static NetherCoreGeometry open(DensityFunction df) {
        return new NetherCoreGeometry.Source(df, CELL_W, CELL_H, NETHER_SEA_LEVEL, BED_Y).open(MIN_Y, MAX_Y);
    }

    /** The expected density: the stub sampled at the 8 surrounding cell corners, interpolated by hand. */
    private static double expected(int sampleX, int worldY, int worldZ) {
        int netherY = NetherCoreGeometry.NETHER_CENTER_Y + (worldY - BED_Y);
        int x0 = Math.floorDiv(sampleX, CELL_W) * CELL_W;
        int z0 = Math.floorDiv(worldZ, CELL_W) * CELL_W;
        int y0 = Math.floorDiv(netherY, CELL_H) * CELL_H;
        double fx = (double) (sampleX - x0) / CELL_W;
        double fz = (double) (worldZ - z0) / CELL_W;
        double fy = (double) (netherY - y0) / CELL_H;

        double bot = bilerp(CountingDensity.f(x0, y0, z0), CountingDensity.f(x0 + CELL_W, y0, z0),
                CountingDensity.f(x0, y0, z0 + CELL_W), CountingDensity.f(x0 + CELL_W, y0, z0 + CELL_W), fx, fz);
        double top = bilerp(CountingDensity.f(x0, y0 + CELL_H, z0), CountingDensity.f(x0 + CELL_W, y0 + CELL_H, z0),
                CountingDensity.f(x0, y0 + CELL_H, z0 + CELL_W), CountingDensity.f(x0 + CELL_W, y0 + CELL_H, z0 + CELL_W), fx, fz);
        return bot + (top - bot) * fy;
    }

    private static double bilerp(double x0z0, double x1z0, double x0z1, double x1z1, double fx, double fz) {
        double z0 = x0z0 + (x1z0 - x0z0) * fx;
        double z1 = x0z1 + (x1z1 - x0z1) * fx;
        return z0 + (z1 - z0) * fz;
    }

    @Test
    @DisplayName("densityAt is the trilinear interpolation of the Nether's own cell corners")
    void densityIsTrilinearOverCellCorners() {
        NetherCoreGeometry geom = open(new CountingDensity());

        for (int sampleX : new int[] {0, 3, 12_000, 12_001, -8, 2048}) {
            for (int worldZ : new int[] {0, 1, 5, -7, 64}) {
                NetherCoreGeometry.Column column = geom.column(sampleX, worldZ);
                for (int y = geom.minCoreY(); y <= geom.maxCoreY(); y += 7) {
                    assertEquals(expected(sampleX, y, worldZ), column.densityAt(y), 0.0,
                            "density at (" + sampleX + "," + y + "," + worldZ + ")");
                }
            }
        }
    }

    @Test
    @DisplayName("each distinct corner is evaluated exactly once; more columns in the cell are pure cache hits")
    void eachDistinctCornerComputedOnce() {
        CountingDensity df = new CountingDensity();
        NetherCoreGeometry geom = open(df);

        // One column touches four distinct corners; each costs one profile of `rows` computes.
        geom.column(0, 0).densityAt(BED_Y);
        int perColumn = df.calls.get();
        assertTrue(perColumn > 0, "the first column must sample the router");

        // Every other block-column inside the same cell shares all four corners → zero further computes.
        for (int x = 0; x < CELL_W; x++) {
            for (int z = 0; z < CELL_W; z++) {
                NetherCoreGeometry.Column column = geom.column(x, z);
                for (int y = geom.minCoreY(); y <= geom.maxCoreY(); y++) {
                    column.densityAt(y);
                }
            }
        }
        assertEquals(perColumn, df.calls.get(), "cache hits must add zero compute() calls");

        // A column in the neighbouring cell shares two corners, so it adds exactly two more profiles.
        geom.column(CELL_W, 0).densityAt(BED_Y);
        assertEquals(perColumn + perColumn / 2, df.calls.get(), "a neighbouring cell shares two corners");
    }

    @Test
    @DisplayName("Y outside the sampled cell rows has no density — the caller must leave those blocks alone")
    void unsampledYHasNoDensity() {
        NetherCoreGeometry geom = open(new CountingDensity());
        NetherCoreGeometry.Column column = geom.column(0, 0);

        // Every Y the band itself covers is sampled...
        for (int y = geom.minCoreY(); y <= geom.maxCoreY(); y++) {
            assertTrue(geom.isSampled(y), "band Y " + y + " must be sampled");
            assertFalse(Double.isNaN(column.densityAt(y)), "band Y " + y + " must have a density");
        }
        // ...and beyond it there is nothing to say. (The cell rows extend a little past the top boundary,
        // which is why this asks well clear of it rather than one block past.)
        assertFalse(geom.isSampled(geom.minCoreY() - 1));
        assertTrue(Double.isNaN(column.densityAt(geom.minCoreY() - 1)));
        assertTrue(Double.isNaN(column.densityAt(geom.maxCoreY() + 64)));
        assertFalse(column.isSolid(geom.minCoreY() - 1), "an unsampled Y is never solid");
    }

    @Test
    @DisplayName("the Nether-space <-> band-Y mapping round-trips and puts the open layer on the track bed")
    void yMappingRoundTrips() {
        NetherCoreGeometry geom = open(new CountingDensity());

        assertEquals(NetherCoreGeometry.NETHER_CENTER_Y, geom.netherY(BED_Y), "Nether centre lands on the bed");
        assertEquals(BED_Y, geom.worldY(NetherCoreGeometry.NETHER_CENTER_Y));
        for (int y = MIN_Y; y < MAX_Y; y += 13) {
            assertEquals(y, geom.worldY(geom.netherY(y)));
        }

        // The band is the Nether's sampled slab translated onto the bed.
        assertEquals(BED_Y + (NetherCoreGeometry.NETHER_Y_SAMPLE_MIN - NetherCoreGeometry.NETHER_CENTER_Y),
                geom.minCoreY());
        assertEquals(BED_Y + (NetherCoreGeometry.NETHER_Y_SAMPLE_MAX - NetherCoreGeometry.NETHER_CENTER_Y),
                geom.maxCoreY());

        // Below the Nether's own sea level a non-solid block is lava, not air.
        assertTrue(geom.isLavaLevel(geom.worldY(NETHER_SEA_LEVEL - 1)));
        assertFalse(geom.isLavaLevel(geom.worldY(NETHER_SEA_LEVEL)));
    }

    @Test
    @DisplayName("floorBelow finds a standable surface, and reports none in an open column")
    void floorBelowFindsStandableSurface() {
        // A Nether that is solid below its centre line and open above it. The exact crossing lands
        // wherever the trilinear interpolation puts it — what matters is that it IS a standable surface.
        DensityFunction slab = new CountingDensity() {
            @Override public double compute(FunctionContext c) {
                return c.blockY() < NetherCoreGeometry.NETHER_CENTER_Y ? 1.0 : -1.0;
            }
        };
        NetherCoreGeometry geom = open(slab);
        NetherCoreGeometry.Column column = geom.column(0, 0);

        int floor = column.floorBelow(geom.maxCoreY());
        assertTrue(floor >= geom.minCoreY() && floor <= geom.maxCoreY(), "the floor is inside the band");
        assertTrue(floor < BED_Y, "solid ground sits below the open layer the train rides through");
        assertTrue(column.isSolid(floor));
        assertFalse(column.isSolid(floor + 1), "a floor must have open space above it");
        assertEquals(floor, column.topSolidY(), "the highest floor is the column's top surface");

        // A wholly open column reports no floor and no top.
        NetherCoreGeometry openGeom = open(new CountingDensity() {
            @Override public double compute(FunctionContext c) { return -1.0; }
        });
        assertEquals(Integer.MIN_VALUE, openGeom.column(0, 0).floorBelow(openGeom.maxCoreY()));
        assertEquals(Integer.MIN_VALUE, openGeom.column(0, 0).topSolidY());
    }
}
