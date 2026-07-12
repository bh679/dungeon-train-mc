package games.brennan.dungeontrain.platform.neoforge;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.platform.event.DtChunkLoadCallback;
import games.brennan.dungeontrain.platform.event.DtEvents;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Thin NeoForge → {@code DtEvents} bridge for {@code ChunkEvent.Load}.
 * Auto-registered via {@link EventBusSubscriber}. Exact semantic passthrough. All
 * DT handlers were NORMAL, so a single subscription firing {@code listeners()} in
 * registration order matches the old bus.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NeoForgeChunkBridge {

    private NeoForgeChunkBridge() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (DtEvents.CHUNK_LOAD.isEmpty()) {
            return;
        }
        for (DtChunkLoadCallback cb : DtEvents.CHUNK_LOAD.listeners()) {
            cb.onChunkLoad(event.getLevel(), event.getChunk(), event.isNewChunk());
        }
    }
}
