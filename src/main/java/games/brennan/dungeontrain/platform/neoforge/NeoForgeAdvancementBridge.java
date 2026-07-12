package games.brennan.dungeontrain.platform.neoforge;
import games.brennan.dungeontrain.DtCore;

import games.brennan.dungeontrain.platform.event.DtAdvancementEarnCallback;
import games.brennan.dungeontrain.platform.event.DtEvents;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;

/**
 * Thin NeoForge → {@code DtEvents} bridge for advancement events.
 * Auto-registered via {@link EventBusSubscriber}. Exact semantic passthrough —
 * no logic.
 */
@EventBusSubscriber(modid = DtCore.MOD_ID)
public final class NeoForgeAdvancementBridge {

    private NeoForgeAdvancementBridge() {}

    @SubscribeEvent
    public static void onAdvancementEarn(AdvancementEvent.AdvancementEarnEvent event) {
        for (DtAdvancementEarnCallback cb : DtEvents.ADVANCEMENT_EARN.listeners()) {
            cb.onAdvancementEarn(event.getEntity(), event.getAdvancement());
        }
    }
}
