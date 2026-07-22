package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.ChuncksBand;
import games.brennan.dungeontrain.worldgen.GenProfiler;
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
 * Carves the top-down slices of the {@link ChuncksBand}. For a {@link ChuncksBand.Kind#SLICE} chunk it
 * erases every block strictly below a flat per-chunk cut Y ({@link ChuncksBand#sliceCutY}), leaving the
 * natural surface on top and a flat bottom — a floating island of the chunk's upper terrain. VOID chunks
 * are already all-air from {@code NoiseBasedChunkGeneratorMixin}; FULL chunks (and every non-slice chunk)
 * are left untouched.
 *
 * <p>Runs on {@link ChunkEvent.Load} gated on {@link ChunkEvent.Load#isNewChunk()} — once at generation,
 * never on reload (player builds survive), after all decoration. The corridor footprint (the tunnel
 * wall-to-wall Z-span, which contains the bed, rails, pillars, and any tunnel carved through the slab) is
 * preserved so the floating track survives the cut — the same guard {@code WorldDisintegrationEvents}
 * uses. Writes go through raw {@link LevelChunkSection#setBlockState}, the Sable-safe path.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WorldChuncksEvents {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private WorldChuncksEvents() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        if (ChuncksBand.startX(level) == ChuncksBand.OFF) return; // band disabled / no train

        ChunkAccess chunk = event.getChunk();
        ChunkPos pos = chunk.getPos();
        if (ChuncksBand.kindOf(level, pos.x, pos.z) != ChuncksBand.Kind.SLICE) return;

        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        CarriageDims dims = data.dims();
        TrackGeometry g = TrackGeometry.from(dims, data.getTrainY());
        int bedY = g.bedY();
        // Preserve the whole corridor structure footprint (bed, rails, pillars, and the tunnel carved
        // through the slab) — wall to wall — so the floating track survives the cut, exactly as the
        // fully-eroded End core does.
        TunnelGeometry tg = TunnelGeometry.from(g);
        int preserveZMin = tg.wallMinZ();
        int preserveZMax = tg.wallMaxZ();

        int cutY = ChuncksBand.sliceCutY(level, pos.x, pos.z, bedY);
        int chunkMinX = pos.getMinBlockX();
        int chunkMinZ = pos.getMinBlockZ();
        boolean changed = false;

        long genT0 = GenProfiler.t0();
        for (int sIdx = 0; sIdx < chunk.getSectionsCount(); sIdx++) {
            LevelChunkSection section = chunk.getSection(sIdx);
            if (section.hasOnlyAir()) continue;
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
            if (baseY >= cutY) continue; // whole section at/above the cut → kept intact
            for (int dx = 0; dx < 16; dx++) {
                int worldX = chunkMinX + dx;
                for (int dz = 0; dz < 16; dz++) {
                    int worldZ = chunkMinZ + dz;
                    if (worldZ >= preserveZMin && worldZ <= preserveZMax) continue; // keep the corridor
                    for (int ly = 0; ly < 16; ly++) {
                        int y = baseY + ly;
                        if (y >= cutY) continue; // above the flat cut → part of the kept surface slab
                        BlockState cur = section.getBlockState(dx, ly, dz);
                        if (cur.isAir()) continue;
                        if (cur.hasBlockEntity()) {
                            chunk.removeBlockEntity(new BlockPos(worldX, y, worldZ));
                        }
                        section.setBlockState(dx, ly, dz, AIR, false);
                        changed = true;
                    }
                }
            }
        }
        GenProfiler.add(GenProfiler.Bucket.CHUNCKS_SLICE, genT0);
        if (changed) chunk.setUnsaved(true);
    }
}
