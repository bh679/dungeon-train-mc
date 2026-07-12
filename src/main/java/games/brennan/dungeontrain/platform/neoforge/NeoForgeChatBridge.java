package games.brennan.dungeontrain.platform.neoforge;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtServerChatCallback;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ServerChatEvent;

/**
 * Thin NeoForge → {@code DtEvents} bridge for {@code ServerChatEvent}.
 * Auto-registered via {@link EventBusSubscriber}. DT's chat listener is
 * observe-only, so this bridge invokes every listener and never touches the
 * event's cancellation or message — exact semantic passthrough.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NeoForgeChatBridge {

    private NeoForgeChatBridge() {}

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        if (DtEvents.SERVER_CHAT.isEmpty()) {
            return;
        }
        for (DtServerChatCallback cb : DtEvents.SERVER_CHAT.listeners()) {
            cb.onChat(event.getPlayer(), event.getRawText(), event.getMessage());
        }
    }
}
