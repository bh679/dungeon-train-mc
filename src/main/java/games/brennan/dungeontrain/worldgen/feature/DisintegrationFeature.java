package games.brennan.dungeontrain.worldgen.feature;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.Disintegration;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.slf4j.Logger;

import java.util.EnumSet;
import java.util.Set;

/**
 * Worldgen feature that grows the <b>End world-gen</b> portion of the disintegration
 * band: floating End-stone islands shaped by the <em>real</em> End noise router and
 * real chorus plants on top. Runs at {@code top_layer_modification} (it needs a
 * {@link WorldGenLevel} for chorus); the surrounding void erosion is done afterwards
 * by {@code WorldDisintegrationEvents} on chunk load (which preserves end stone +
 * chorus), so trees that spill in from neighbouring chunks are cleaned up too.
 *
 * <p>Island shape comes from {@code endLevel.getChunkSource().randomState().router()
 * .finalDensity()} — the actual {@code minecraft:end} terrain density (2D island
 * function + {@code BASE_3D_NOISE_END} 3D noise + the End vertical slide). We sample
 * it in the outer End (offset on X past the inner void ring) so we get authentic
 * scattered islands, and translate the End's island Y band onto track level. End
 * blocks are stamped with raw section writes; chorus uses the normal worldgen path.</p>
 */
public class DisintegrationFeature extends Feature<NoneFeatureConfiguration> {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 2D End island function (vanilla, fixed seed 0) — cheap per-column prefilter. */
    private static final DensityFunction END_ISLANDS_2D = DensityFunctions.endIslands(0L);

    /** X offset into the outer End (past the ~1024-block inner void ring) so we sample real scattered islands. */
    private static final int ISLAND_SAMPLE_OFFSET_X = 16_000;
    /** End-space Y that maps onto the track's bed Y (the End's islands cluster around here). */
    private static final int END_ISLAND_CENTER_Y = 56;
    /**
     * End-space Y range to sample — the whole slid End island band. We translate the entire band
     * onto track level rather than clipping it to a fixed window, so islands keep their full natural
     * taper (a fixed window sliced taller islands flat, reading as "huge chunks missing").
     */
    private static final int END_Y_SAMPLE_MIN = 8;
    private static final int END_Y_SAMPLE_MAX = 120;
    /**
     * Skip columns whose 2D island value is below this before the costly 3D sampling. Kept very low
     * (near the function's −0.84 minimum) so it only rejects deep void, never clips an island fringe
     * that the 3D noise would have filled.
     */
    private static final double ISLAND_2D_PREFILTER = -0.8;

    /** Blocks of clearance kept island-free on each side of the track lane (so the train has a clear path). */
    private static final int CORRIDOR_ISLAND_MARGIN = 2;
    /** Air pocket (blocks) cleared above an island top so a chorus plant can grow there. */
    private static final int CHORUS_POCKET = 10;
    /** Max chorus attempts per chunk — vanilla {@code CountPlacement.of(UniformInt.of(0, 4))}. */
    private static final int CHORUS_COUNT_BOUND = 5;

    private static final Set<Heightmap.Types> WG_HEIGHTMAPS = EnumSet.of(
            Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR_WG,
            Heightmap.Types.WORLD_SURFACE_WG);

    public DisintegrationFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        try {
            WorldGenLevel level = ctx.level();
            ChunkPos cp = new ChunkPos(ctx.origin());
            ServerLevel serverLevel = level.getLevel();
            MinecraftServer server = serverLevel.getServer();
            if (server == null) return false;
            ServerLevel overworld = server.overworld();
            if (overworld == null) return false;

            long startX = DisintegrationBand.startX(overworld);
            int chunkMinX = cp.getMinBlockX();
            if (chunkMinX + 15 < startX) return false; // before the first band (or disabled)

            // Real End terrain density (2D islands + BASE_3D_NOISE_END + End slide) and the End's
            // noise-cell size, so we can trilinearly interpolate exactly like the End's NoiseChunk.
            ServerLevel end = server.getLevel(Level.END);
            if (end == null) return false;
            DensityFunction endDensity = end.getChunkSource().randomState().router().finalDensity();
            int cellW = 8;
            int cellH = 4;
            if (end.getChunkSource().getGenerator() instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator nbg) {
                net.minecraft.world.level.levelgen.NoiseSettings ns = nbg.generatorSettings().value().noiseSettings();
                cellW = ns.getCellWidth();
                cellH = ns.getCellHeight();
            }

            DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
            CarriageDims dims = data.dims();
            TrackGeometry g = TrackGeometry.from(dims, data.getTrainY());
            int bedY = g.bedY();
            int zMin = g.trackZMin();
            int zMax = g.trackZMax();
            double[] endRamp = new double[16];
            boolean anyEnd = false;
            for (int dx = 0; dx < 16; dx++) {
                endRamp[dx] = DisintegrationBand.endIslandRampAt(overworld, chunkMinX + dx);
                if (endRamp[dx] > 0.0) anyEnd = true;
            }
            if (!anyEnd) return false;

            ChunkAccess chunk = level.getChunk(cp.x, cp.z);
            int chunkMinZ = cp.getMinBlockZ();
            int minY = level.getMinBuildHeight();
            int maxY = level.getMaxBuildHeight() - 1;

            int[] islandTop = new int[256];
            java.util.Arrays.fill(islandTop, Integer.MIN_VALUE);
            boolean changed = false;

            // Stamp real-End-shaped islands. For each column we sample the End density at the
            // noise-cell corners (cellW × cellH grid, world-anchored — same as the End's NoiseChunk)
            // and trilinearly interpolate per block, so island edges taper exactly like the real End.
            int yLo = Math.max(minY, bedY + (END_Y_SAMPLE_MIN - END_ISLAND_CENTER_Y));
            int yHi = Math.min(maxY, bedY + (END_Y_SAMPLE_MAX - END_ISLAND_CENTER_Y));
            int endYLo = END_ISLAND_CENTER_Y + (yLo - bedY);
            int cellRowBase = Math.floorDiv(endYLo, cellH);
            int rows = Math.floorDiv(END_ISLAND_CENTER_Y + (yHi - bedY), cellH) - cellRowBase + 2;
            double[] c00 = new double[rows];
            double[] c10 = new double[rows];
            double[] c01 = new double[rows];
            double[] c11 = new double[rows];

            for (int dx = 0; dx < 16; dx++) {
                double e = endRamp[dx];
                if (e <= 0.0) continue;
                int sampleX = chunkMinX + dx + ISLAND_SAMPLE_OFFSET_X;
                int x0 = Math.floorDiv(sampleX, cellW) * cellW;
                int x1 = x0 + cellW;
                double fx = (double) (sampleX - x0) / cellW;
                double threshold = Disintegration.islandDensityThreshold(e);
                for (int dz = 0; dz < 16; dz++) {
                    int worldZ = chunkMinZ + dz;
                    if (worldZ >= zMin - CORRIDOR_ISLAND_MARGIN && worldZ <= zMax + CORRIDOR_ISLAND_MARGIN) continue;
                    // Cheap 2D prefilter — skip deep End void columns before the costly 3D sampling.
                    if (END_ISLANDS_2D.compute(new DensityFunction.SinglePointContext(sampleX, 0, worldZ)) < ISLAND_2D_PREFILTER) {
                        continue;
                    }
                    int z0 = Math.floorDiv(worldZ, cellW) * cellW;
                    int z1 = z0 + cellW;
                    double fz = (double) (worldZ - z0) / cellW;

                    // Density at the four XZ cell corners for every cell-Y boundary spanning the island band.
                    for (int r = 0; r < rows; r++) {
                        int by = (cellRowBase + r) * cellH;
                        c00[r] = endDensity.compute(new DensityFunction.SinglePointContext(x0, by, z0));
                        c10[r] = endDensity.compute(new DensityFunction.SinglePointContext(x1, by, z0));
                        c01[r] = endDensity.compute(new DensityFunction.SinglePointContext(x0, by, z1));
                        c11[r] = endDensity.compute(new DensityFunction.SinglePointContext(x1, by, z1));
                    }

                    int top = Integer.MIN_VALUE;
                    for (int myY = yLo; myY <= yHi; myY++) {
                        int endY = END_ISLAND_CENTER_Y + (myY - bedY);
                        if (endY < 0 || endY > 127) continue;
                        int r = Math.floorDiv(endY, cellH) - cellRowBase;
                        double fy = (double) (endY - (cellRowBase + r) * cellH) / cellH;
                        double bot = bilerp(c00[r], c10[r], c01[r], c11[r], fx, fz);
                        double topD = bilerp(c00[r + 1], c10[r + 1], c01[r + 1], c11[r + 1], fx, fz);
                        double d = bot + (topD - bot) * fy;
                        if (d <= threshold) continue;
                        setRaw(chunk, dx, myY, dz, Blocks.END_STONE.defaultBlockState());
                        top = myY;
                        changed = true;
                    }
                    if (top != Integer.MIN_VALUE) islandTop[dx * 16 + dz] = top;
                }
            }

            if (!changed) return false;

            Heightmap.primeHeightmaps(chunk, WG_HEIGHTMAPS);

            // Grow real chorus plants — matching vanilla's distribution exactly: 0-4 attempts per chunk
            // (CountPlacement), random X/Z (InSquarePlacement), and ONLY in the end_highlands biome (the
            // sole End biome that carries CHORUS_PLANT). We query the real End biome source at the sample
            // column so chorus lands in the same places it would in the real End — far sparser than before.
            net.minecraft.world.level.biome.BiomeSource endBiomes = end.getChunkSource().getGenerator().getBiomeSource();
            net.minecraft.world.level.biome.Climate.Sampler endSampler = end.getChunkSource().randomState().sampler();
            ChunkGenerator generator = ctx.chunkGenerator();
            RandomSource random = ctx.random();
            int count = random.nextInt(CHORUS_COUNT_BOUND);
            for (int i = 0; i < count; i++) {
                int dx = random.nextInt(16);
                int dz = random.nextInt(16);
                if (endRamp[dx] <= 0.0) continue;
                int top = islandTop[dx * 16 + dz];
                if (top == Integer.MIN_VALUE || top + 1 > maxY) continue;
                int sampleX = chunkMinX + dx + ISLAND_SAMPLE_OFFSET_X;
                int worldZ = chunkMinZ + dz;
                int endY = END_ISLAND_CENTER_Y + (top - bedY);
                net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome = endBiomes.getNoiseBiome(
                        net.minecraft.core.QuartPos.fromBlock(sampleX),
                        net.minecraft.core.QuartPos.fromBlock(endY),
                        net.minecraft.core.QuartPos.fromBlock(worldZ), endSampler);
                if (!biome.is(net.minecraft.world.level.biome.Biomes.END_HIGHLANDS)) continue;
                for (int dy = 1; dy <= CHORUS_POCKET && top + dy <= maxY; dy++) {
                    setRaw(chunk, dx, top + dy, dz, Blocks.AIR.defaultBlockState());
                }
                BlockPos pos = new BlockPos(chunkMinX + dx, top + 1, worldZ);
                Feature.CHORUS_PLANT.place(NoneFeatureConfiguration.INSTANCE, level, generator, random, pos);
            }

            chunk.setUnsaved(true);
            return true;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] DisintegrationFeature.place failed at chunk {}", ctx.origin(), t);
            return false;
        }
    }

    /** Bilinear blend of the four XZ cell corners (d at x0z0, x1z0, x0z1, x1z1). */
    private static double bilerp(double x0z0, double x1z0, double x0z1, double x1z1, double fx, double fz) {
        double z0 = x0z0 + (x1z0 - x0z0) * fx;
        double z1 = x0z1 + (x1z1 - x0z1) * fx;
        return z0 + (z1 - z0) * fz;
    }

    /** Raw block stamp into the section owning {@code y} (no level-side hooks; heightmaps re-primed after). */
    private static void setRaw(ChunkAccess chunk, int dx, int y, int dz, net.minecraft.world.level.block.state.BlockState state) {
        int sIdx = chunk.getSectionIndex(y);
        if (sIdx < 0 || sIdx >= chunk.getSectionsCount()) return;
        LevelChunkSection section = chunk.getSection(sIdx);
        int ly = y - SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
        // Drop any orphaned block entity (e.g. a structure chest) before overwriting the block,
        // otherwise the chunk logs "Invalid block entity ... got air/end_stone" on load.
        if (section.getBlockState(dx, ly, dz).hasBlockEntity()) {
            chunk.removeBlockEntity(new BlockPos(chunk.getPos().getMinBlockX() + dx, y, chunk.getPos().getMinBlockZ() + dz));
        }
        section.setBlockState(dx, ly, dz, state, false);
    }
}
