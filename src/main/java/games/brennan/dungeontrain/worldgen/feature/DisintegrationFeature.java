package games.brennan.dungeontrain.worldgen.feature;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
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
    /** Vertical reach (blocks) around the bed within which islands may form. */
    private static final int ISLAND_Y_RADIUS = 40;
    /** Skip columns whose 2D island value is below this (deep End void) before the costly 3D sampling. */
    private static final double ISLAND_2D_PREFILTER = -0.55;

    /** Blocks of clearance kept island-free on each side of the track lane (so the train has a clear path). */
    private static final int CORRIDOR_ISLAND_MARGIN = 2;
    /** Air pocket (blocks) cleared above an island top so a chorus plant can grow there. */
    private static final int CHORUS_POCKET = 10;
    /** Per-island-top chance (scaled by End intensity) to grow a chorus plant, capped per chunk. */
    private static final float CHORUS_CHANCE = 0.06f;
    private static final int MAX_CHORUS_PER_CHUNK = 6;

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

            long[] band = DisintegrationBand.range(overworld);
            if (band == null) return false;
            long startX = band[0];
            long bandLen = band[1] - band[0];
            int chunkMinX = cp.getMinBlockX();
            if (!Disintegration.chunkInBand(chunkMinX, chunkMinX + 15, startX, bandLen)) return false;

            // Real End terrain density (2D islands + BASE_3D_NOISE_END + End slide).
            ServerLevel end = server.getLevel(Level.END);
            if (end == null) return false;
            DensityFunction endDensity = end.getChunkSource().randomState().router().finalDensity();

            DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
            CarriageDims dims = data.dims();
            TrackGeometry g = TrackGeometry.from(dims, data.getTrainY());
            int bedY = g.bedY();
            int zMin = g.trackZMin();
            int zMax = g.trackZMax();
            int fade = DungeonTrainCommonConfig.getDisintegrationFadeBlocks();
            int voidHold = DungeonTrainCommonConfig.getDisintegrationVoidHoldBlocks();
            int endHold = DungeonTrainCommonConfig.getDisintegrationEndHoldBlocks();

            double[] endRamp = new double[16];
            boolean anyEnd = false;
            for (int dx = 0; dx < 16; dx++) {
                endRamp[dx] = Disintegration.endRamp(chunkMinX + dx, startX, fade, voidHold, endHold);
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

            // Stamp real-End-shaped islands per column where the End density is solid.
            for (int dx = 0; dx < 16; dx++) {
                double e = endRamp[dx];
                if (e <= 0.0) continue;
                int worldX = chunkMinX + dx;
                int sampleX = worldX + ISLAND_SAMPLE_OFFSET_X;
                double threshold = Disintegration.islandDensityThreshold(e);
                for (int dz = 0; dz < 16; dz++) {
                    int worldZ = chunkMinZ + dz;
                    if (worldZ >= zMin - CORRIDOR_ISLAND_MARGIN && worldZ <= zMax + CORRIDOR_ISLAND_MARGIN) continue;
                    // Cheap 2D prefilter — skip deep End void columns before the 3D sampling.
                    double island2d = END_ISLANDS_2D.compute(new DensityFunction.SinglePointContext(sampleX, 0, worldZ));
                    if (island2d < ISLAND_2D_PREFILTER) continue;

                    int top = Integer.MIN_VALUE;
                    for (int myY = Math.max(minY, bedY - ISLAND_Y_RADIUS);
                         myY <= Math.min(maxY, bedY + ISLAND_Y_RADIUS); myY++) {
                        int endY = END_ISLAND_CENTER_Y + (myY - bedY);
                        if (endY < 0 || endY > 127) continue;
                        double d = endDensity.compute(new DensityFunction.SinglePointContext(sampleX, endY, worldZ));
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

            // Grow real chorus plants on island tops (clear an air pocket first; overworld terrain is still
            // present at this gen step and is eroded away later on chunk load).
            ChunkGenerator generator = ctx.chunkGenerator();
            RandomSource random = ctx.random();
            int placed = 0;
            for (int dx = 0; dx < 16 && placed < MAX_CHORUS_PER_CHUNK; dx++) {
                double e = endRamp[dx];
                if (e <= 0.0) continue;
                int worldX = chunkMinX + dx;
                for (int dz = 0; dz < 16 && placed < MAX_CHORUS_PER_CHUNK; dz++) {
                    int top = islandTop[dx * 16 + dz];
                    if (top == Integer.MIN_VALUE || top + 1 > maxY) continue;
                    if (random.nextFloat() >= CHORUS_CHANCE * e) continue;
                    for (int dy = 1; dy <= CHORUS_POCKET && top + dy <= maxY; dy++) {
                        setRaw(chunk, dx, top + dy, dz, Blocks.AIR.defaultBlockState());
                    }
                    BlockPos pos = new BlockPos(worldX, top + 1, chunkMinZ + dz);
                    if (Feature.CHORUS_PLANT.place(NoneFeatureConfiguration.INSTANCE, level, generator, random, pos)) {
                        placed++;
                    }
                }
            }

            chunk.setUnsaved(true);
            return true;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] DisintegrationFeature.place failed at chunk {}", ctx.origin(), t);
            return false;
        }
    }

    /** Raw block stamp into the section owning {@code y} (no level-side hooks; heightmaps re-primed after). */
    private static void setRaw(ChunkAccess chunk, int dx, int y, int dz, net.minecraft.world.level.block.state.BlockState state) {
        int sIdx = chunk.getSectionIndex(y);
        if (sIdx < 0 || sIdx >= chunk.getSectionsCount()) return;
        LevelChunkSection section = chunk.getSection(sIdx);
        int ly = y - SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
        section.setBlockState(dx, ly, dz, state, false);
    }
}
