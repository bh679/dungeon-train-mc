package games.brennan.dungeontrain.platform.neoforge;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.platform.event.DtClientCommandRegistrationCallback;
import games.brennan.dungeontrain.platform.event.DtEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Thin NeoForge → {@code DtEvents} bridge for client-command registration
 * ({@code RegisterClientCommandsEvent}, game bus, client only). Fires every
 * listener in registration order into the event's dispatcher — pure passthrough.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class NeoForgeClientCommandBridge {

    private NeoForgeClientCommandBridge() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        for (DtClientCommandRegistrationCallback cb : DtEvents.CLIENT_COMMAND_REGISTRATION.listeners()) {
            cb.register(event.getDispatcher(), event.getBuildContext());
        }
    }
}
