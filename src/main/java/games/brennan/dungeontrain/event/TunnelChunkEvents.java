package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Mirror of {@link TrackChunkEvents} for tunnel fill. Enqueues newly-loaded
 * chunks that intersect the 13-wide tunnel corridor to the train's pending
 * tunnel queue, where {@code TunnelGenerator.fillRenderDistance} drains them
 * on a rate-limited tick schedule.
 *
 * <p>Deferred paint avoids a repeat of the 17-second server-thread stall seen
 * when track fills ran synchronously on freshly-spawned ships.</p>
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TunnelChunkEvents {

    private TunnelChunkEvents() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!DungeonTrainConfig.getGenerateTunnels()) return;

        ChunkAccess chunk = event.getChunk();
        ChunkPos pos = chunk.getPos();
        int cx = pos.x;
        int cz = pos.z;
        if (TrackGenerator.isShipyardChunk(cx, cz)) return;

        int chunkMinZ = cz << 4;
        int chunkMaxZ = chunkMinZ + 15;
        long chunkKey = ChunkPos.asLong(cx, cz);

        for (LoadedServerShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            if (!(ship.getTransformProvider() instanceof TrainTransformProvider provider)) continue;
            TrackGeometry g = provider.getTrackGeometry();
            if (g == null) continue;
            TunnelGeometry tg = TunnelGeometry.from(g);
            // Tunnel corridor is wider than track corridor (±4 vs ±2) — use the
            // tunnel wall range for the prefilter so we don't miss edge chunks.
            if (chunkMaxZ < tg.wallMinZ() || chunkMinZ > tg.wallMaxZ()) continue;
            if (provider.getTunnelFilledChunks().contains(chunkKey)) continue;
            provider.getPendingTunnelChunks().add(chunkKey);
        }
    }
}
