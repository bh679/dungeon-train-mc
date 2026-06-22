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
 * Carves the <b>world disintegration band</b> into freshly-generated Dungeon
 * Train overworld chunks: past a carriage-count line the terrain (and the track's
 * support pillars) progressively erode into void, leaving a fully-void core where
 * only the floating track survives, then reassemble into normal terrain. See
 * {@link Disintegration} for the band math.
 *
 * <p>Runs from {@link ChunkEvent.Load} gated on {@link ChunkEvent.Load#isNewChunk()}
 * — so it fires <b>once at first generation</b>, never on reload. That is what
 * keeps player builds inside the void from being wiped every time the chunk
 * reloads, and it runs after all worldgen (including
 * {@code TrackBedFeature}) so the bed + rails already exist and we simply preserve
 * them while eroding everything else.</p>
 *
 * <p>Erosion writes go through {@link LevelChunkSection#setBlockState} (raw
 * section stamp), exactly as {@link BedrockFloorEvents} does and for the same
 * reason: {@code LevelChunk.setBlockState} is mixed into by Sable to update its
 * physics neighbourhood, which livelocks when called from the chunk-load
 * completion handler while neighbours are still mid-generation. Section writes
 * skip every level-side hook (block updates, light, physics, drops); we mark the
 * chunk unsaved so the change persists.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WorldDisintegrationEvents {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

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
                DungeonTrainCommonConfig.getDisintegrationCoreBlocks());
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
        int chunkMaxX = chunkMinX + 15;
        // Fast reject — the ~all chunks outside the forward band do zero work.
        if (!Disintegration.chunkInBand(chunkMinX, chunkMaxX, startX, bandLen)) return;

        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        CarriageDims dims = data.dims();
        TrackGeometry g = TrackGeometry.from(dims, data.getTrainY());
        int bedY = g.bedY();
        int railY = g.railY();
        int zMin = g.trackZMin();
        int zMax = g.trackZMax();
        long seed = data.getGenerationSeed();
        int fade = DungeonTrainCommonConfig.getDisintegrationFadeBlocks();
        int core = DungeonTrainCommonConfig.getDisintegrationCoreBlocks();

        // Per-column void ramp — identical for every section, so compute once.
        double[] colRamp = new double[16];
        boolean anyColumn = false;
        for (int dx = 0; dx < 16; dx++) {
            double r = Disintegration.voidRamp(chunkMinX + dx, startX, fade, core);
            colRamp[dx] = r;
            if (r > 0.0) anyColumn = true;
        }
        if (!anyColumn) return; // chunk straddles the band edge but no column is inside

        int chunkMinZ = pos.getMinBlockZ();
        boolean changed = false;

        for (int sIdx = 0; sIdx < chunk.getSectionsCount(); sIdx++) {
            LevelChunkSection section = chunk.getSection(sIdx);
            if (section.hasOnlyAir()) continue; // nothing to erode in empty sky/void sections
            int sectionBaseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));

            for (int dx = 0; dx < 16; dx++) {
                double ramp = colRamp[dx];
                if (ramp <= 0.0) continue;
                int worldX = chunkMinX + dx;
                for (int dz = 0; dz < 16; dz++) {
                    int worldZ = chunkMinZ + dz;
                    boolean corridorZ = worldZ >= zMin && worldZ <= zMax;
                    for (int ly = 0; ly < 16; ly++) {
                        int y = sectionBaseY + ly;
                        // Never erode the track the train rides on.
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
        if (changed) chunk.setUnsaved(true);
    }
}
