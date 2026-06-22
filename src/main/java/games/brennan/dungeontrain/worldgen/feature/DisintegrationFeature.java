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
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
 * Worldgen feature that carves the <b>disintegration band</b> into the overworld:
 * Overworld → Void → End world-gen → Void → Overworld. Runs at
 * {@code top_layer_modification} <i>after</i> {@code dungeontrain:track_bed} (same
 * biome modifier, so order is fixed), once per chunk at generation — so player
 * builds survive and the post-feature light step relights the result.
 *
 * <p>For each band chunk it (1) erodes overworld terrain — including the track's
 * support pillars — to void per the {@link Disintegration#middleRamp}, preserving
 * only the bed + rails; (2) stamps floating End-stone islands per the
 * {@link Disintegration#endRamp}, shaped by the <b>real</b> End island density
 * function ({@link DensityFunctions#endIslands}) sampled in the outer End; and
 * (3) grows real chorus plants on the island tops via {@link Feature#CHORUS_PLANT}.
 * Lighting stays overworld-level (a separate task makes it exact End lighting).</p>
 *
 * <p>Terrain edits use raw {@link LevelChunkSection#setBlockState} writes for speed
 * (heightmaps are re-primed afterwards so skylight and chorus placement sit right),
 * mirroring {@code BedrockFloorEvents}; only chorus uses the normal worldgen
 * {@code setBlock} path (it needs a {@link WorldGenLevel}).</p>
 */
public class DisintegrationFeature extends Feature<NoneFeatureConfiguration> {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState END_STONE = Blocks.END_STONE.defaultBlockState();

    /** Real, seed-independent End island density function (vanilla, fixed seed 0). */
    private static final DensityFunction END_ISLANDS = DensityFunctions.endIslands(0L);
    /** X offset into the outer End (well past the ~1024-block inner void ring) so we sample real scattered islands. */
    private static final int ISLAND_SAMPLE_OFFSET_X = 16_000;
    /** Blocks of clearance kept island-free on each side of the track lane (so the train has a clear path). */
    private static final int CORRIDOR_ISLAND_MARGIN = 2;
    /** Per-island-top chance (scaled by End intensity) to grow a chorus plant, capped per chunk. */
    private static final float CHORUS_CHANCE = 0.05f;
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

            DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
            CarriageDims dims = data.dims();
            TrackGeometry g = TrackGeometry.from(dims, data.getTrainY());
            int bedY = g.bedY();
            int railY = g.railY();
            int zMin = g.trackZMin();
            int zMax = g.trackZMax();
            long seed = data.getGenerationSeed();
            int fade = DungeonTrainCommonConfig.getDisintegrationFadeBlocks();
            int voidHold = DungeonTrainCommonConfig.getDisintegrationVoidHoldBlocks();
            int endHold = DungeonTrainCommonConfig.getDisintegrationEndHoldBlocks();

            ChunkAccess chunk = level.getChunk(cp.x, cp.z);
            int chunkMinZ = cp.getMinBlockZ();

            double[] middle = new double[16];
            double[] end = new double[16];
            boolean anyMiddle = false;
            boolean anyEnd = false;
            for (int dx = 0; dx < 16; dx++) {
                int worldX = chunkMinX + dx;
                middle[dx] = Disintegration.middleRamp(worldX, startX, fade, voidHold, endHold);
                end[dx] = Disintegration.endRamp(worldX, startX, fade, voidHold, endHold);
                if (middle[dx] > 0.0) anyMiddle = true;
                if (end[dx] > 0.0) anyEnd = true;
            }
            if (!anyMiddle && !anyEnd) return false;

            boolean changed = false;

            // Pass 1 — erode overworld terrain (and pillars) to void per the middle ramp.
            if (anyMiddle) {
                for (int sIdx = 0; sIdx < chunk.getSectionsCount(); sIdx++) {
                    LevelChunkSection section = chunk.getSection(sIdx);
                    if (section.hasOnlyAir()) continue;
                    int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
                    for (int dx = 0; dx < 16; dx++) {
                        double ramp = middle[dx];
                        if (ramp <= 0.0) continue;
                        int worldX = chunkMinX + dx;
                        for (int dz = 0; dz < 16; dz++) {
                            int worldZ = chunkMinZ + dz;
                            boolean corridorZ = worldZ >= zMin && worldZ <= zMax;
                            for (int ly = 0; ly < 16; ly++) {
                                int y = baseY + ly;
                                if (corridorZ && (y == bedY || y == railY)) continue;
                                double p = Disintegration.removalProbabilityFromRamp(ramp, y, bedY);
                                if (p <= 0.0) continue;
                                if (Disintegration.coherentNoise(seed, worldX, y, worldZ) >= p) continue;
                                if (section.getBlockState(dx, ly, dz).isAir()) continue;
                                section.setBlockState(dx, ly, dz, AIR, false);
                                changed = true;
                            }
                        }
                    }
                }
            }

            // Pass 2 — stamp real End-shaped islands per the end ramp; record each column's top for chorus.
            int minY = level.getMinBuildHeight();
            int maxY = level.getMaxBuildHeight() - 1;
            int[] islandTop = new int[256];
            java.util.Arrays.fill(islandTop, Integer.MIN_VALUE);
            if (anyEnd) {
                for (int dx = 0; dx < 16; dx++) {
                    double e = end[dx];
                    if (e <= 0.0) continue;
                    int worldX = chunkMinX + dx;
                    for (int dz = 0; dz < 16; dz++) {
                        int worldZ = chunkMinZ + dz;
                        // Keep the track lane (plus a margin) clear so the train has a path.
                        if (worldZ >= zMin - CORRIDOR_ISLAND_MARGIN && worldZ <= zMax + CORRIDOR_ISLAND_MARGIN) continue;
                        double density = END_ISLANDS.compute(
                                new DensityFunction.SinglePointContext(worldX + ISLAND_SAMPLE_OFFSET_X, 0, worldZ));
                        int half = Disintegration.islandHalfThickness(density, e);
                        if (half <= 0) continue;
                        int centerY = bedY + (int) Math.round(
                                (Disintegration.coherentNoise(seed, worldX, 1234, worldZ) - 0.5)
                                        * 2.0 * Disintegration.ISLAND_VERTICAL_SPREAD);
                        int yLo = Math.max(minY, centerY - half);
                        int yHi = Math.min(maxY, centerY + half);
                        for (int y = yLo; y <= yHi; y++) {
                            setRaw(chunk, dx, y, dz);
                            changed = true;
                        }
                        if (yHi >= yLo) islandTop[dx * 16 + dz] = yHi;
                    }
                }
            }

            if (!changed) return false;

            // Re-prime heightmaps so the post-feature light step and chorus placement see the new tops.
            Heightmap.primeHeightmaps(chunk, WG_HEIGHTMAPS);

            // Pass 3 — grow real chorus plants on island tops, denser toward the End core.
            if (anyEnd) {
                ChunkGenerator generator = ctx.chunkGenerator();
                RandomSource random = ctx.random();
                int placed = 0;
                for (int dx = 0; dx < 16 && placed < MAX_CHORUS_PER_CHUNK; dx++) {
                    double e = end[dx];
                    if (e <= 0.0) continue;
                    int worldX = chunkMinX + dx;
                    for (int dz = 0; dz < 16 && placed < MAX_CHORUS_PER_CHUNK; dz++) {
                        int top = islandTop[dx * 16 + dz];
                        if (top == Integer.MIN_VALUE || top >= maxY) continue;
                        if (random.nextFloat() >= CHORUS_CHANCE * e) continue;
                        BlockPos pos = new BlockPos(worldX, top + 1, chunkMinZ + dz);
                        if (Feature.CHORUS_PLANT.place(NoneFeatureConfiguration.INSTANCE, level, generator, random, pos)) {
                            placed++;
                        }
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

    /** Raw End-stone stamp into the section owning {@code y} (no level-side hooks; heightmaps re-primed after). */
    private static void setRaw(ChunkAccess chunk, int dx, int y, int dz) {
        int sIdx = chunk.getSectionIndex(y);
        if (sIdx < 0 || sIdx >= chunk.getSectionsCount()) return;
        LevelChunkSection section = chunk.getSection(sIdx);
        int ly = y - SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
        section.setBlockState(dx, ly, dz, END_STONE, false);
    }
}
