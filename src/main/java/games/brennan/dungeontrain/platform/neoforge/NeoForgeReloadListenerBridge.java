package games.brennan.dungeontrain.platform.neoforge;

import games.brennan.dungeontrain.DtCore;
import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtReloadListenerRegistrationCallback;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

/**
 * Thin NeoForge → {@code DtEvents.SERVER_RELOAD_LISTENER_REGISTRATION} bridge for the
 * game-bus {@code AddReloadListenerEvent} (server datapack channel). Auto-registered
 * via {@link EventBusSubscriber}. Each handler is fed a registrar that delegates to
 * {@code event.addListener}; exact passthrough. A Fabric bridge would iterate the same
 * listeners into {@code ResourceManagerHelper.get(SERVER_DATA)}.
 */
@EventBusSubscriber(modid = DtCore.MOD_ID)
public final class NeoForgeReloadListenerBridge {

    private NeoForgeReloadListenerBridge() {}

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        for (DtReloadListenerRegistrationCallback cb :
                DtEvents.SERVER_RELOAD_LISTENER_REGISTRATION.listeners()) {
            cb.registerReloadListeners(event::addListener);
        }
    }
}
