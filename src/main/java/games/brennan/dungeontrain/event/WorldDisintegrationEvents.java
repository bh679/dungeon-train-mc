package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.Disintegration;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import net.minecraft.core.BlockPos;
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
 * Erodes overworld terrain (and the track's support pillars) to void across the
 * disintegration band, per the {@link Disintegration#middleRamp}. Runs on
 * {@link ChunkEvent.Load} gated on {@link ChunkEvent.Load#isNewChunk()} — so it fires
 * once at generation, never on reload (player builds survive), and crucially runs
 * <b>after all worldgen decoration of every chunk</b>, so vegetation (trees/leaves)
 * that spills in from neighbouring chunks is cleaned up too.
 *
 * <p>Preserved: the track bed + rails (by geometry) and the End-stone islands +
 * chorus plants that {@code DisintegrationFeature} placed during generation (by block
 * type) — everything else in the band is dissolved. Writes go through raw
 * {@link LevelChunkSection#setBlockState}, the Sable-safe path (see
 * {@link BedrockFloorEvents}).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WorldDisintegrationEvents {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private WorldDisintegrationEvents() {}

    /** Blocks the End-gen feature placed — never eroded, so the islands and chorus float in the void. */
    private static boolean isPreservedEndBlock(BlockState state) {
        return state.is(Blocks.END_STONE) || state.is(Blocks.CHORUS_PLANT) || state.is(Blocks.CHORUS_FLOWER);
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;

        long startX = DisintegrationBand.startX(level);

        ChunkAccess chunk = event.getChunk();
        ChunkPos pos = chunk.getPos();
        int chunkMinX = pos.getMinBlockX();
        if (chunkMinX + 15 < startX) return; // before the first band (or disabled)

        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        CarriageDims dims = data.dims();
        TrackGeometry g = TrackGeometry.from(dims, data.getTrainY());
        int bedY = g.bedY();
        int railY = g.railY();
        int zMin = g.trackZMin();
        int zMax = g.trackZMax();
        long seed = data.getGenerationSeed();

        double[] middle = new double[16];
        boolean anyMiddle = false;
        for (int dx = 0; dx < 16; dx++) {
            middle[dx] = DisintegrationBand.middleRampAt(level, chunkMinX + dx);
            if (middle[dx] > 0.0) anyMiddle = true;
        }
        if (!anyMiddle) return;

        int chunkMinZ = pos.getMinBlockZ();
        boolean changed = false;

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
                        BlockState cur = section.getBlockState(dx, ly, dz);
                        if (cur.isAir() || isPreservedEndBlock(cur)) continue;
                        if (cur.hasBlockEntity()) {
                            chunk.removeBlockEntity(new BlockPos(worldX, y, worldZ));
                        }
                        section.setBlockState(dx, ly, dz, AIR, false);
                        changed = true;
                    }
                }
            }
        }
        if (changed) chunk.setUnsaved(true);
    }
}
