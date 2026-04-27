package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Enqueues newly-loaded server chunks for deferred track filling. Does NOT
 * paint synchronously — painting on the chunk-load tick was observed to wedge
 * the server thread for 17+ seconds while VS was still settling a freshly-
 * spawned ship (moved while colliding with unloaded ships → snap-back loop).
 *
 * <p>Paired with the periodic drain in {@link TrainTickEvents}, which pops
 * one chunk per tick-period from {@link TrainTransformProvider#getPendingChunks()}
 * via {@link TrackGenerator#fillRenderDistance}. That scan also covers chunks
 * already loaded at spawn (which never fire Load after this listener
 * registers).</p>
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TrackChunkEvents {

    private TrackChunkEvents() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!DungeonTrainConfig.getGenerateTracks()) return;

        ChunkAccess chunk = event.getChunk();
        ChunkPos pos = chunk.getPos();
        int cx = pos.x;
        int cz = pos.z;
        if (TrackGenerator.isShipyardChunk(cx, cz)) return;

        int chunkMinZ = cz << 4;
        int chunkMaxZ = chunkMinZ + 15;
        long chunkKey = ChunkPos.asLong(cx, cz);

        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            if (!(ship.getKinematicDriver() instanceof TrainTransformProvider provider)) continue;
            TrackGeometry g = provider.getTrackGeometry();
            if (g == null) continue;
            // Fast Z-corridor prefilter — skip chunks clearly outside this train's strip.
            if (chunkMaxZ < g.trackZMin() || chunkMinZ > g.trackZMax()) continue;
            // Don't re-queue chunks we've already painted.
            if (provider.getFilledChunks().contains(chunkKey)) continue;
            // offer() appends to the tail — drain pops from head, so order
            // matches load order ≈ spatial proximity to the player.
            provider.getPendingChunks().offer(chunkKey);
        }
    }
}
