package games.brennan.dungeontrain.platform.neoforge;

import games.brennan.dungeontrain.DtCore;
import games.brennan.dungeontrain.platform.event.DtBlockEntityTypeAddBlocksCallback;
import games.brennan.dungeontrain.platform.event.DtEvents;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent;

/**
 * Thin NeoForge → {@code DtEvents.BLOCK_ENTITY_TYPE_ADD_BLOCKS} bridge for the mod-bus
 * {@code BlockEntityTypeAddBlocksEvent}. Auto-registered via {@link EventBusSubscriber}
 * (NeoForge 1.21.1 auto-routes {@code IModBusEvent} subscribers to the mod bus). NOT
 * {@code Dist.CLIENT}-gated — the lectern binding is needed on both logical sides. Each
 * handler is fed a registrar that delegates to {@code event.modify}; exact passthrough.
 */
@EventBusSubscriber(modid = DtCore.MOD_ID)
public final class NeoForgeBlockEntityBridge {

    private NeoForgeBlockEntityBridge() {}

    @SubscribeEvent
    public static void onAddBlocks(BlockEntityTypeAddBlocksEvent event) {
        for (DtBlockEntityTypeAddBlocksCallback cb :
                DtEvents.BLOCK_ENTITY_TYPE_ADD_BLOCKS.listeners()) {
            cb.addBlocks(event::modify);
        }
    }
}
