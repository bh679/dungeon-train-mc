package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.Disintegration;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Carves the <b>world disintegration band</b> into freshly-generated Dungeon Train
 * overworld chunks: past a carriage-count line the run crosses
 * Overworld → Void → End world-gen → Void → Overworld. See {@link Disintegration}
 * for the band math (a {@code middleRamp} M drives erosion, an {@code endRamp} E
 * drives floating End-stone island fill).
 *
 * <p>Runs from {@link ChunkEvent.Load} gated on {@link ChunkEvent.Load#isNewChunk()}
 * — fires <b>once at first generation</b>, never on reload, so player builds inside
 * the band survive. It runs after all worldgen (including {@code TrackBedFeature}),
 * so the bed + rails already exist and are simply preserved.</p>
 *
 * <p>Block writes go through {@link LevelChunkSection#setBlockState} (raw section
 * stamp), as {@link BedrockFloorEvents} does and for the same reason: vanilla
 * {@code LevelChunk.setBlockState} is mixed into by Sable and livelocks when called
 * from the chunk-load handler. Section writes skip every level-side hook.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WorldDisintegrationEvents {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState END_STONE = Blocks.END_STONE.defaultBlockState();

    private WorldDisintegrationEvents() {}

    /**
     * Band X-range {@code [startX, endX)} for this overworld, or {@code null} if
     * disintegration is disabled or the world has no train. Shared with
     * {@link BedrockFloorEvents} so the bedrock floor is suppressed in-band.
     */
    public static long[] bandRange(ServerLevel overworld) {
        if (!DungeonTrainCommonConfig.isDisintegrationEnabled()) return null;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.startsWithTrain()) return null;
        int length = data.dims().length();
        long startX = Disintegration.bandStartX(
                DungeonTrainCommonConfig.getDisintegrationStartCarriages(), length);
        long bandLen = Disintegration.bandLength(
                DungeonTrainCommonConfig.getDisintegrationFadeBlocks(),
                DungeonTrainCommonConfig.getDisintegrationVoidHoldBlocks(),
                DungeonTrainCommonConfig.getDisintegrationEndHoldBlocks());
        if (bandLen <= 0) return null;
        return new long[] { startX, startX + bandLen };
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;

        long[] band = bandRange(level);
        if (band == null) return;
        long startX = band[0];
        long bandLen = band[1] - band[0];

        ChunkAccess chunk = event.getChunk();
        ChunkPos pos = chunk.getPos();
        int chunkMinX = pos.getMinBlockX();
        if (!Disintegration.chunkInBand(chunkMinX, chunkMinX + 15, startX, bandLen)) return;

        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
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

        // Per-column ramps — identical for every section, so compute once.
        double[] middle = new double[16];
        double[] end = new double[16];
        boolean anyMiddle = false;
        boolean anyEnd = false;
        for (int dx = 0; dx < 16; dx++) {
            int worldX = chunkMinX + dx;
            double m = Disintegration.middleRamp(worldX, startX, fade, voidHold, endHold);
            double e = Disintegration.endRamp(worldX, startX, fade, voidHold, endHold);
            middle[dx] = m;
            end[dx] = e;
            if (m > 0.0) anyMiddle = true;
            if (e > 0.0) anyEnd = true;
        }
        if (!anyMiddle && !anyEnd) return; // chunk straddles a band edge but no column is inside

        int chunkMinZ = pos.getMinBlockZ();
        boolean changed = false;

        // Pass 1 — erosion: dissolve overworld (and any prior) terrain to void per M.
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

        // Pass 2 — End world-gen: place floating End-stone islands around track level per E.
        if (anyEnd) {
            int yMin = Math.max(level.getMinBuildHeight(), bedY - Disintegration.ISLAND_VERTICAL_RADIUS);
            int yMax = Math.min(level.getMaxBuildHeight() - 1, bedY + Disintegration.ISLAND_VERTICAL_RADIUS);
            for (int sIdx = 0; sIdx < chunk.getSectionsCount(); sIdx++) {
                int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
                if (baseY + 15 < yMin || baseY > yMax) continue; // section outside island band
                LevelChunkSection section = chunk.getSection(sIdx);
                for (int dx = 0; dx < 16; dx++) {
                    double e = end[dx];
                    if (e <= 0.0) continue;
                    int worldX = chunkMinX + dx;
                    for (int dz = 0; dz < 16; dz++) {
                        int worldZ = chunkMinZ + dz;
                        boolean corridorZ = worldZ >= zMin && worldZ <= zMax;
                        for (int ly = 0; ly < 16; ly++) {
                            int y = baseY + ly;
                            if (y < yMin || y > yMax) continue;
                            if (corridorZ && (y == bedY || y == railY)) continue;
                            double p = Disintegration.endFillProbabilityFromRamp(e, y, bedY);
                            if (p <= 0.0) continue;
                            if (Disintegration.islandNoise(seed, worldX, y, worldZ) >= p) continue;
                            section.setBlockState(dx, ly, dz, END_STONE, false);
                            changed = true;
                        }
                    }
                }
            }
        }

        if (changed) chunk.setUnsaved(true);
    }
}
