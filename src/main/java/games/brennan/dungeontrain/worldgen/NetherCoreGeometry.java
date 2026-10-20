package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.density.NetherCoreBiomes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;

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
 * {@link #NETHER_CENTER_Y}). The sampling itself — trilinear interpolation between the Nether's own
 * noise-cell corners with a per-instance corner memo — is {@link CellDensityGeometry}, shared with the
 * Deeper Dark core's {@link OthersideGeometry}.</p>
 *
 * <p>An instance owns a corner-column memo and is therefore <b>not thread-safe</b>: open one per chunk /
 * per placement attempt ({@link Source#open}) and let it go. Callers that walk a whole column must
 * {@link #column} once and reuse it rather than calling a per-block lookup.</p>
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
    public static final double NO_DENSITY = CellDensityGeometry.NO_DENSITY;

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
    private final CellDensityGeometry cells;

    private NetherCoreGeometry(Source source, int worldMinY, int worldMaxY) {
        this.source = source;
        this.cells = new CellDensityGeometry(source.netherDensity(), source.cellW(), source.cellH(),
                NETHER_CENTER_Y, NETHER_Y_SAMPLE_MIN, NETHER_Y_SAMPLE_MAX, source.bedY(), worldMinY, worldMaxY);
    }

    /** The X to sample the Nether density at, for a band column already resolved to its edge-waved X. */
    public static int sampleX(int wavedX) {
        return wavedX + NETHER_SAMPLE_OFFSET_X;
    }

    /** Lowest world Y core terrain can occupy. */
    public int minCoreY() {
        return cells.minY();
    }

    /** Highest world Y core terrain can occupy. */
    public int maxCoreY() {
        return cells.maxY();
    }

    /** The track bed's world Y — {@link #NETHER_CENTER_Y} in Nether space. */
    public int bedY() {
        return source.bedY();
    }

    /** True when the geometry has any sampled rows at all (a degenerate world could clamp them away). */
    public boolean isUsable() {
        return cells.isUsable();
    }

    /** Nether-space Y for a band world Y. */
    public int netherY(int worldY) {
        return cells.spaceY(worldY);
    }

    /** Band world Y for a Nether-space Y — the inverse of {@link #netherY}. */
    public int worldY(int netherY) {
        return cells.worldY(netherY);
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
     * hoisted out. Walk a column through one of these — {@link Column#densityAt} on a fresh column per
     * block would re-do four memo lookups per Y on the hottest path in Nether generation.
     */
    public Column column(int sampleX, int worldZ) {
        return new Column(cells.column(sampleX, worldZ));
    }

    /** True when {@code worldY} falls inside the sampled cell rows — outside it the density is undefined. */
    public boolean isSampled(int worldY) {
        return cells.isSampled(worldY);
    }

    /** @see #column */
    public static final class Column {

        private final CellDensityGeometry.Column inner;

        private Column(CellDensityGeometry.Column inner) {
            this.inner = inner;
        }

        /**
         * Real-Nether terrain density at this column's {@code worldY}, or {@link #NO_DENSITY} when the Y
         * falls outside the sampled cell rows (the caller should leave such blocks alone rather than treat
         * them as air).
         */
        public double densityAt(int worldY) {
            return inner.densityAt(worldY);
        }

        /** True when the core is solid netherrack at this world Y. */
        public boolean isSolid(int worldY) {
            return inner.isSolid(worldY);
        }

        /**
         * World Y of the topmost solid core block in this column, or {@link Integer#MIN_VALUE} when the
         * column is open the whole way down (lava lake or cavern).
         */
        public int topSolidY() {
            return inner.topSolidY();
        }

        /**
         * World Y of the highest solid core block at or below {@code startY} whose immediate neighbour
         * above is open — i.e. a floor something can stand on — or {@link Integer#MIN_VALUE} if there is
         * none down to {@link #minCoreY()}. This is the band's replacement for vanilla's
         * {@code ChunkGenerator.getBaseColumn} scan, which in the band would read the overworld mountain
         * the core replaces.
         */
        public int floorBelow(int startY) {
            return inner.floorBelow(startY);
        }

        /** The Z this column was opened at — handy for callers threading columns around. */
        public int worldZ() {
            return inner.worldZ();
        }
    }
}
