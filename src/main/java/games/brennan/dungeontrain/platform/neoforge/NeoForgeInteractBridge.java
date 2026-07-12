package games.brennan.dungeontrain.platform.neoforge;
import games.brennan.dungeontrain.DtCore;

import games.brennan.dungeontrain.platform.event.DtEntityInteractCallback;
import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtRightClickItemCallback;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Thin NeoForge → {@code DtEvents} bridge for the non-cancelling
 * {@code PlayerInteractEvent} sub-events (RightClickItem, EntityInteract).
 * Auto-registered via {@link EventBusSubscriber}. Exact semantic passthrough. All
 * DT handlers here are NORMAL-priority observers that never cancel.
 *
 * <p>The {@code RightClickBlock} sub-event is intentionally NOT bridged: it has two
 * cancelling handlers ({@code PrefabUseHandler}, HIGH, and {@code LetterLecternEvents})
 * that call {@code setCanceled(true)} + {@code setCancellationResult(...)}, and
 * {@code PrefabUseHandler} passes the event object into a helper — so it keeps its
 * NeoForge {@code @SubscribeEvent}s. See {@code NeoForgeServerEvents} for the
 * rationale.</p>
 */
@EventBusSubscriber(modid = DtCore.MOD_ID)
public final class NeoForgeInteractBridge {

    private NeoForgeInteractBridge() {}

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        for (DtRightClickItemCallback cb : DtEvents.RIGHT_CLICK_ITEM.listeners()) {
            cb.onRightClickItem(event.getEntity(), event.getLevel(), event.getItemStack());
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        for (DtEntityInteractCallback cb : DtEvents.ENTITY_INTERACT.listeners()) {
            cb.onEntityInteract(event.getEntity(), event.getLevel(), event.getItemStack(), event.getTarget());
        }
    }
}
