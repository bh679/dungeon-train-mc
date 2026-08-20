package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.density.NetherCoreBiomes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;

import java.util.HashMap;
import java.util.Map;

/**
 * Where the Nether band's core terrain is — the single source of truth for the netherrack/lava/air shape,
 * shared by {@link games.brennan.dungeontrain.worldgen.feature.NetherTransitionFeature} (which stamps it)
 * and the {@code worldgen.structure.Band*Structure} classes (which site fortresses, bastions, fossils and
 * ruined portals into it). Both must agree block-for-block, so neither reimplements the sampling.
 *
 * <p>This is the Nether-side twin of {@link EndIslandGeometry}. Shape comes from the <em>real</em> Nether's
 * terrain density ({@code netherLevel.getChunkSource().randomState().router().finalDensity()}), sampled at
 * an X offset ({@link #sampleX}) so successive bands get different — but internally continuous — Nether,
 * and translated from the Nether's open layer onto the train's track level ({@code bedY} ↔
 * {@link #NETHER_CENTER_Y}). Columns are trilinearly interpolated between the Nether's own noise-cell
 * corners exactly the way the Nether's {@code NoiseChunk} does.</p>
 *
 * <p>An instance owns a corner-column memo and is therefore <b>not thread-safe</b>: open one per chunk /
 * per placement attempt ({@link Source#open}) and let it go. The memo is what keeps full-resolution
 * sampling affordable — the four cell corners a column interpolates between are shared by every column in
 * that cell, so without it each column re-walks the whole Nether density tree (~16k evaluations per chunk;
 * this is the confirmed dominant Nether-crossing generation cost, {@code GenProfiler.Bucket.CORE_REPLACE}).
 * Callers that walk a whole column must {@link #column} once and reuse it rather than calling a
 * per-block lookup, which would re-do the four corner lookups for every Y.</p>
 */
public final class NetherCoreGeometry {

    /**
     * X offset into the Nether so successive bands sample different (but continuous) terrain. Shared with
     * the biome sampler so terrain, biome and surface skin all describe the same patch of Nether.
     */
    public static final int NETHER_SAMPLE_OFFSET_X = NetherCoreBiomes.SAMPLE_OFFSET_X;
    /** Nether-space Y mapped onto the track bed (the open Nether layer sits around here). */
    public static final int NETHER_CENTER_Y = 40;
    /** Nether-space Y rows sampled for the core terrain. */
    public static final int NETHER_Y_SAMPLE_MIN = 8;
    public static final int NETHER_Y_SAMPLE_MAX = 120;

    /** Returned by the density accessors for a Y outside the sampled cell rows. */
    public static final double NO_DENSITY = Double.NaN;

    /**
     * The per-world Nether-density handle: everything needed to {@link #open} a geometry, resolved once
     * from the live Nether dimension. Cheap to hold (the density function and cell dims are immutable), so
     * the server-start snapshot in {@link games.brennan.dungeontrain.worldgen.density.NetherBandContext}
     * keeps one and worldgen workers never touch the server to get it.
     *
     * @param netherDensity  the real Nether's {@code finalDensity} router output
     * @param cellW          the Nether's noise-cell width (interpolation grid, world-anchored)
     * @param cellH          the Nether's noise-cell height
     * @param netherSeaLevel the Nether's sea level — below it, non-solid is lava rather than air
     * @param bedY           the track bed's world Y — where {@link #NETHER_CENTER_Y} lands
     */
    public record Source(DensityFunction netherDensity, int cellW, int cellH, int netherSeaLevel, int bedY) {

        /**
         * Capture the live Nether dimension's density router. Returns {@code null} when the world has no
         * Nether (or anything fails) — callers then simply generate no core terrain and no structures.
         */
        public static Source resolve(MinecraftServer server, int bedY) {
            if (server == null) return null;
            ServerLevel nether = server.getLevel(Level.NETHER);
            if (nether == null) return null;
            DensityFunction netherDensity = nether.getChunkSource().randomState().router().finalDensity();
            int cellW = 4;
            int cellH = 8;
            int netherSeaLevel = 32;
            if (nether.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator nbg) {
                NoiseGeneratorSettings gs = nbg.generatorSettings().value();
                NoiseSettings ns = gs.noiseSettings();
                cellW = ns.getCellWidth();
                cellH = ns.getCellHeight();
                netherSeaLevel = gs.seaLevel();
            }
            return new Source(netherDensity, cellW, cellH, netherSeaLevel, bedY);
        }

        /** A fresh geometry (with its own corner memo) clamped to this world's build limits. */
        public NetherCoreGeometry open(int worldMinY, int worldMaxY) {
            return new NetherCoreGeometry(this, worldMinY, worldMaxY);
        }
    }

    private final Source source;
    private final int yLo;         // lowest world Y the core band can occupy
    private final int yHi;         // highest world Y the core band can occupy
    private final int cellRowBase; // cell-row index of the first sampled Nether-space Y boundary
    private final int rows;        // number of cell-Y boundaries per corner column
    private final Map<Long, double[]> cornerColumns = new HashMap<>();

    private NetherCoreGeometry(Source source, int worldMinY, int worldMaxY) {
        this.source = source;
        this.yLo = Math.max(worldMinY, source.bedY() + (NETHER_Y_SAMPLE_MIN - NETHER_CENTER_Y));
        this.yHi = Math.min(worldMaxY, source.bedY() + (NETHER_Y_SAMPLE_MAX - NETHER_CENTER_Y));
        int netherYLo = NETHER_CENTER_Y + (yLo - source.bedY());
        this.cellRowBase = Math.floorDiv(netherYLo, source.cellH());
        this.rows = Math.floorDiv(NETHER_CENTER_Y + (yHi - source.bedY()), source.cellH()) - cellRowBase + 2;
    }

    /** The X to sample the Nether density at, for a band column already resolved to its edge-waved X. */
    public static int sampleX(int wavedX) {
        return wavedX + NETHER_SAMPLE_OFFSET_X;
    }

    /** Lowest world Y core terrain can occupy. */
    public int minCoreY() {
        return yLo;
    }

    /** Highest world Y core terrain can occupy. */
    public int maxCoreY() {
        return yHi;
    }

    /** The track bed's world Y — {@link #NETHER_CENTER_Y} in Nether space. */
    public int bedY() {
        return source.bedY();
    }

    /** True when the geometry has any sampled rows at all (a degenerate world could clamp them away). */
    public boolean isUsable() {
        return yLo <= yHi && rows >= 2;
    }

    /** Nether-space Y for a band world Y. */
    public int netherY(int worldY) {
        return NETHER_CENTER_Y + (worldY - source.bedY());
    }

    /** Band world Y for a Nether-space Y — the inverse of {@link #netherY}. */
    public int worldY(int netherY) {
        return source.bedY() + (netherY - NETHER_CENTER_Y);
    }

    /**
     * True when a non-solid block at this world Y is lava rather than air — i.e. it sits below the
     * Nether's own sea level, translated onto track level.
     */
    public boolean isLavaLevel(int worldY) {
        return netherY(worldY) < source.netherSeaLevel();
    }

    /**
     * A single band column's view of the Nether density, with the four shared noise-cell corner profiles
     * hoisted out. Walk a column through one of these — {@link #densityAt} on a fresh column per block
     * would re-do four memo lookups per Y on the hottest path in Nether generation.
     */
    public Column column(int sampleX, int worldZ) {
        return new Column(sampleX, worldZ);
    }

    /** True when {@code worldY} falls inside the sampled cell rows — outside it the density is undefined. */
    public boolean isSampled(int worldY) {
        if (worldY < yLo || worldY > yHi) return false;
        int r = Math.floorDiv(netherY(worldY), source.cellH()) - cellRowBase;
        return r >= 0 && r + 1 < rows;
    }

    /** @see #column */
    public final class Column {

        private final int worldZ;
        private final double fx;
        private final double fz;
        private final double[] c00;
        private final double[] c10;
        private final double[] c01;
        private final double[] c11;

        private Column(int sampleX, int worldZ) {
            this.worldZ = worldZ;
            int cellW = source.cellW();
            int x0 = Math.floorDiv(sampleX, cellW) * cellW;
            int x1 = x0 + cellW;
            this.fx = (double) (sampleX - x0) / cellW;
            int z0 = Math.floorDiv(worldZ, cellW) * cellW;
            int z1 = z0 + cellW;
            this.fz = (double) (worldZ - z0) / cellW;
            this.c00 = cornerColumn(x0, z0);
            this.c10 = cornerColumn(x1, z0);
            this.c01 = cornerColumn(x0, z1);
            this.c11 = cornerColumn(x1, z1);
        }

        /**
         * Real-Nether terrain density at this column's {@code worldY}, or {@link #NO_DENSITY} when the Y
         * falls outside the sampled cell rows (the caller should leave such blocks alone rather than treat
         * them as air).
         */
        public double densityAt(int worldY) {
            int netherY = NETHER_CENTER_Y + (worldY - source.bedY());
            int cellH = source.cellH();
            int r = Math.floorDiv(netherY, cellH) - cellRowBase;
            if (r < 0 || r + 1 >= rows) return NO_DENSITY;
            double fy = (double) (netherY - (cellRowBase + r) * cellH) / cellH;
            double bot = bilerp(c00[r], c10[r], c01[r], c11[r], fx, fz);
            double top = bilerp(c00[r + 1], c10[r + 1], c01[r + 1], c11[r + 1], fx, fz);
            return bot + (top - bot) * fy;
        }

        /** True when the core is solid netherrack at this world Y. */
        public boolean isSolid(int worldY) {
            double d = densityAt(worldY);
            return d > 0.0;   // NaN compares false — an unsampled Y is not solid
        }

        /**
         * World Y of the topmost solid core block in this column, or {@link Integer#MIN_VALUE} when the
         * column is open the whole way down (lava lake or cavern).
         */
        public int topSolidY() {
            for (int y = yHi; y >= yLo; y--) {
                if (isSolid(y)) return y;
            }
            return Integer.MIN_VALUE;
        }

        /**
         * World Y of the highest solid core block at or below {@code startY} whose immediate neighbour
         * above is open — i.e. a floor something can stand on — or {@link Integer#MIN_VALUE} if there is
         * none down to {@link #minCoreY()}. This is the band's replacement for vanilla's
         * {@code ChunkGenerator.getBaseColumn} scan, which in the band would read the overworld mountain
         * the core replaces.
         */
        public int floorBelow(int startY) {
            int from = Math.min(startY, yHi);
            boolean openAbove = from >= yHi || !isSolid(from + 1);
            for (int y = from; y >= yLo; y--) {
                boolean solid = isSolid(y);
                if (solid && openAbove) return y;
                openAbove = !solid;
            }
            return Integer.MIN_VALUE;
        }

        /** The Z this column was opened at — handy for callers threading columns around. */
        public int worldZ() {
            return worldZ;
        }
    }

    /**
     * Real-Nether density column at a single noise-cell corner, sampled at each cell-Y boundary spanning
     * the core band. Memoised per geometry instance (keyed by the packed corner XZ) — a pure memo of a
     * pure {@code compute}, so it is byte-identical to re-walking the density tree, removing only
     * duplicate evaluations.
     *
     * <p>Correctness rests on {@code cellRowBase}, {@code rows} and {@code cellH} being constant for the
     * life of the instance (they derive from {@code bedY} and the build limits, not from the column), so a
     * corner's profile is independent of which column requests it.</p>
     */
    private double[] cornerColumn(int cornerX, int cornerZ) {
        long key = (((long) cornerX) << 32) ^ (cornerZ & 0xFFFFFFFFL);
        double[] col = cornerColumns.get(key);
        if (col != null) return col;
        col = new double[rows];
        for (int r = 0; r < rows; r++) {
            int by = (cellRowBase + r) * source.cellH();
            col[r] = source.netherDensity().compute(new DensityFunction.SinglePointContext(cornerX, by, cornerZ));
        }
        cornerColumns.put(key, col);
        return col;
    }

    private static double bilerp(double d00, double d10, double d01, double d11, double fx, double fz) {
        double a = d00 + (d10 - d00) * fx;
        double b = d01 + (d11 - d01) * fx;
        return a + (b - a) * fz;
    }
}
