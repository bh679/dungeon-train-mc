package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Mirror of {@link TrackChunkEvents} for tunnel fill. Enqueues newly-loaded
 * chunks that intersect the 13-wide tunnel corridor to the train's pending
 * tunnel queue, where {@code TunnelGenerator.fillRenderDistance} drains them
 * on a rate-limited tick schedule.
 *
 * <p>Deferred paint avoids a repeat of the 17-second server-thread stall seen
 * when track fills ran synchronously on freshly-spawned ships.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TunnelChunkEvents {

    /**
     * How long after a chunk's most recent save event we treat the chunk as
     * "save in flight" and skip tunnel paint mutations on it. 2.5s comfortably
     * covers worst-case IOWorker serialise duration on slow disks while still
     * letting paint resume promptly. See {@link #isSaveBusy}.
     */
    private static final long SAVE_BUSY_WINDOW_NANOS = 2_500_000_000L;

    /**
     * chunkKey → System.nanoTime() of the most recent ChunkDataEvent.Save.
     * Populated on the IOWorker thread (event fires from chunk save path);
     * read on the server thread before tunnel paint mutates a chunk.
     * ConcurrentHashMap + nanoTime is the right pair for cross-thread reads
     * without taking a server-thread-only gameTime read.
     *
     * Lazy-GC: stale entries are removed on the read that observes them
     * (compareAndRemove via {@link java.util.Map#remove(Object, Object)}),
     * so map size is bounded by chunks saved within the last
     * {@link #SAVE_BUSY_WINDOW_NANOS}.
     */
    private static final ConcurrentHashMap<Long, Long> RECENT_SAVE_NANOS = new ConcurrentHashMap<>();

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

        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            if (!(ship.getKinematicDriver() instanceof TrainTransformProvider provider)) continue;
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

    /**
     * Records the chunk's save timestamp so concurrent tunnel paint can
     * back off while the IOWorker is iterating the chunk's NBT — see
     * {@link #isSaveBusy}. Mitigates a sporadic
     * ConcurrentModificationException inside CompoundTag.write on the
     * IOWorker thread under heavy paint load.
     */
    @SubscribeEvent
    public static void onChunkSave(ChunkDataEvent.Save event) {
        if (!(event.getLevel() instanceof ServerLevel)) return;
        ChunkPos pos = event.getChunk().getPos();
        RECENT_SAVE_NANOS.put(ChunkPos.asLong(pos.x, pos.z), System.nanoTime());
    }

    /**
     * True if a save event for this chunk fired within
     * {@link #SAVE_BUSY_WINDOW_NANOS}. Callers on the server thread should
     * defer block mutations on the chunk until this returns false — the
     * upstream race is between server-thread mutation and IOWorker NBT
     * iteration, and the busy window is sized to outlast the worst-case
     * serialise duration.
     */
    public static boolean isSaveBusy(long chunkKey) {
        Long last = RECENT_SAVE_NANOS.get(chunkKey);
        if (last == null) return false;
        if (System.nanoTime() - last > SAVE_BUSY_WINDOW_NANOS) {
            RECENT_SAVE_NANOS.remove(chunkKey, last);
            return false;
        }
        return true;
    }
}
