package games.brennan.dungeontrain.worldgen;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseSettings;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Where the End band's floating islands are — the single source of truth for the island shape, shared by
 * {@link games.brennan.dungeontrain.worldgen.feature.DisintegrationFeature} (which stamps the End stone)
 * and {@link games.brennan.dungeontrain.worldgen.structure.BandEndCityStructure} (which sites End cities
 * on top of it). Both must agree block-for-block, so neither reimplements the sampling.
 *
 * <p>Shape comes from the <em>real</em> End's terrain density
 * ({@code endLevel.getChunkSource().randomState().router().finalDensity()} — the 2D island function +
 * {@code BASE_3D_NOISE_END} + the End vertical slide), sampled in the outer End (offset
 * {@link #ISLAND_SAMPLE_OFFSET_X} past the inner void ring) so we get authentic scattered islands, and
 * translated from the End's island Y band onto the train's track level ({@code bedY} ↔
 * {@link #END_ISLAND_CENTER_Y}). Columns are trilinearly interpolated between the End's own noise-cell
 * corners exactly the way the End's {@code NoiseChunk} does, so island edges taper identically.</p>
 *
 * <p>An instance owns a corner-column memo and is therefore <b>not thread-safe</b>: open one per chunk /
 * per placement attempt (see {@link Source#open}) and let it go. The memo is what keeps full-resolution
 * sampling cheap — the four cell corners a column interpolates between are shared by every column in that
 * 8×8 cell, so without it each column re-walks the whole End density tree (~30k evaluations per chunk).</p>
 */
public final class EndIslandGeometry {

    /** X offset into the outer End (past the ~1024-block inner void ring) so we sample real scattered islands. */
    public static final int ISLAND_SAMPLE_OFFSET_X = 16_000;
    /** End-space Y that maps onto the track's bed Y (the End's islands cluster around here). */
    public static final int END_ISLAND_CENTER_Y = 56;
    /**
     * End-space Y range sampled — the whole slid End island band. The entire band is translated onto
     * track level rather than clipped to a fixed window, so islands keep their full natural taper (a
     * fixed window sliced taller islands flat, reading as "huge chunks missing").
     */
    public static final int END_Y_SAMPLE_MIN = 8;
    public static final int END_Y_SAMPLE_MAX = 120;

    /** Highest End-space Y the density is defined for (the End's build height). */
    private static final int END_MAX_Y = 127;

    /**
     * The per-world End-density handle: everything needed to {@link #open} a geometry, resolved once from
     * the live End dimension. Cheap to hold (the density function and cell dims are immutable), so the
     * server-start snapshot in {@link games.brennan.dungeontrain.worldgen.density.NetherBandContext} keeps
     * one and worldgen workers never touch the server to get it.
     *
     * @param endDensity the real End's {@code finalDensity} router output
     * @param cellW      the End's noise-cell width  (interpolation grid, world-anchored)
     * @param cellH      the End's noise-cell height
     * @param bedY       the track bed's world Y — where {@link #END_ISLAND_CENTER_Y} lands
     */
    public record Source(DensityFunction endDensity, int cellW, int cellH, int bedY) {

        /**
         * Capture the live End dimension's density router. Returns {@code null} when the world has no End
         * (or anything fails) — callers then simply generate no islands and no cities.
         */
        public static Source resolve(MinecraftServer server, int bedY) {
            if (server == null) return null;
            ServerLevel end = server.getLevel(Level.END);
            if (end == null) return null;
            DensityFunction endDensity = end.getChunkSource().randomState().router().finalDensity();
            int cellW = 8;
            int cellH = 4;
            if (end.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator nbg) {
                NoiseSettings ns = nbg.generatorSettings().value().noiseSettings();
                cellW = ns.getCellWidth();
                cellH = ns.getCellHeight();
            }
            return new Source(endDensity, cellW, cellH, bedY);
        }

        /** A fresh geometry (with its own corner memo) clamped to this world's build limits. */
        public EndIslandGeometry open(int worldMinY, int worldMaxY) {
            return new EndIslandGeometry(this, worldMinY, worldMaxY);
        }
    }

    private final Source source;
    private final int yLo;         // lowest world Y the island band can occupy
    private final int yHi;         // highest world Y the island band can occupy
    private final int cellRowBase; // cell-row index of the first sampled End-space Y boundary
    private final int rows;        // number of cell-Y boundaries per corner column
    private final Map<Long, double[]> cornerColumns = new HashMap<>();

    private EndIslandGeometry(Source source, int worldMinY, int worldMaxY) {
        this.source = source;
        this.yLo = Math.max(worldMinY, source.bedY() + (END_Y_SAMPLE_MIN - END_ISLAND_CENTER_Y));
        this.yHi = Math.min(worldMaxY, source.bedY() + (END_Y_SAMPLE_MAX - END_ISLAND_CENTER_Y));
        int endYLo = END_ISLAND_CENTER_Y + (yLo - source.bedY());
        this.cellRowBase = Math.floorDiv(endYLo, source.cellH());
        this.rows = Math.floorDiv(END_ISLAND_CENTER_Y + (yHi - source.bedY()), source.cellH()) - cellRowBase + 2;
    }

    /** Lowest world Y an island block can occupy. */
    public int minIslandY() {
        return yLo;
    }

    /** Highest world Y an island block can occupy. */
    public int maxIslandY() {
        return yHi;
    }

    /**
     * Feed every world Y at {@code (worldX, worldZ)} that is inside an island to {@code solidY}, ascending.
     * {@code endRamp} is the column's End-island fill ramp (see
     * {@link DisintegrationBand#endIslandRampAt}) — it selects the density threshold, so island edges thin
     * out toward the band's void margins.
     */
    public void forEachSolidY(int worldX, int worldZ, double endRamp, IntConsumer solidY) {
        if (endRamp <= 0.0) return;
        int sampleX = worldX + ISLAND_SAMPLE_OFFSET_X;
        int cellW = source.cellW();
        int cellH = source.cellH();
        int x0 = Math.floorDiv(sampleX, cellW) * cellW;
        int x1 = x0 + cellW;
        double fx = (double) (sampleX - x0) / cellW;
        int z0 = Math.floorDiv(worldZ, cellW) * cellW;
        int z1 = z0 + cellW;
        double fz = (double) (worldZ - z0) / cellW;
        double threshold = Disintegration.islandDensityThreshold(endRamp);

        // The four shared corner columns of End density spanning the island band (memoised).
        double[] col00 = cornerColumn(x0, z0);
        double[] col10 = cornerColumn(x1, z0);
        double[] col01 = cornerColumn(x0, z1);
        double[] col11 = cornerColumn(x1, z1);

        for (int worldY = yLo; worldY <= yHi; worldY++) {
            int endY = END_ISLAND_CENTER_Y + (worldY - source.bedY());
            if (endY < 0 || endY > END_MAX_Y) continue;
            int r = Math.floorDiv(endY, cellH) - cellRowBase;
            double fy = (double) (endY - (cellRowBase + r) * cellH) / cellH;
            double bot = bilerp(col00[r], col10[r], col01[r], col11[r], fx, fz);
            double top = bilerp(col00[r + 1], col10[r + 1], col01[r + 1], col11[r + 1], fx, fz);
            double d = bot + (top - bot) * fy;
            if (d <= threshold) continue;
            solidY.accept(worldY);
        }
    }

    /**
     * World Y of the topmost island block at this column, or {@link Integer#MIN_VALUE} when the column is
     * open void. This is the island <em>surface</em> — what an End city stands on.
     */
    public int topSolidY(int worldX, int worldZ, double endRamp) {
        int[] top = {Integer.MIN_VALUE};
        forEachSolidY(worldX, worldZ, endRamp, y -> top[0] = y);
        return top[0];
    }

    /**
     * End-density column at a single noise-cell corner, sampled at each cell-Y boundary spanning the
     * island band. Memoised per geometry instance (keyed by the packed corner XZ) — deterministic per
     * {@code (x, y, z)}, so the memo is byte-identical to re-walking the density tree.
     */
    private double[] cornerColumn(int cornerX, int cornerZ) {
        long key = (((long) cornerX) << 32) | (cornerZ & 0xFFFFFFFFL);
        double[] col = cornerColumns.get(key);
        if (col != null) return col;
        col = new double[rows];
        for (int r = 0; r < rows; r++) {
            int by = (cellRowBase + r) * source.cellH();
            col[r] = source.endDensity().compute(new DensityFunction.SinglePointContext(cornerX, by, cornerZ));
        }
        cornerColumns.put(key, col);
        return col;
    }

    /** Bilinear blend of the four XZ cell corners (d at x0z0, x1z0, x0z1, x1z1). */
    private static double bilerp(double x0z0, double x1z0, double x0z1, double x1z1, double fx, double fz) {
        double z0 = x0z0 + (x1z0 - x0z0) * fx;
        double z1 = x0z1 + (x1z1 - x0z1) * fx;
        return z0 + (z1 - z0) * fz;
    }
}
