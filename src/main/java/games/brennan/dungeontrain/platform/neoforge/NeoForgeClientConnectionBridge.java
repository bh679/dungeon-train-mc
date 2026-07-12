package games.brennan.dungeontrain.platform.neoforge;
import games.brennan.dungeontrain.DtCore;

import games.brennan.dungeontrain.platform.event.DtClientLoggingCallback;
import games.brennan.dungeontrain.platform.event.DtEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * Thin NeoForge → {@code DtEvents} bridge for the client player-network events
 * ({@code LoggingIn}/{@code LoggingOut}). Client-only so it never loads on a
 * dedicated server. Subscribes each once and fires the matching {@code DtEvents}
 * field in registration order — pure passthrough, no logic.
 */
@EventBusSubscriber(modid = DtCore.MOD_ID, value = Dist.CLIENT)
public final class NeoForgeClientConnectionBridge {

    private NeoForgeClientConnectionBridge() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        for (DtClientLoggingCallback cb : DtEvents.CLIENT_LOGGING_IN.listeners()) {
            cb.onClientLogging();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        for (DtClientLoggingCallback cb : DtEvents.CLIENT_LOGGING_OUT.listeners()) {
            cb.onClientLogging();
        }
    }
}
