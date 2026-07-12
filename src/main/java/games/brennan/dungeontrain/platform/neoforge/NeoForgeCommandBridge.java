package games.brennan.dungeontrain.platform.neoforge;
import games.brennan.dungeontrain.DtCore;

import games.brennan.dungeontrain.platform.event.DtCommandCallback;
import games.brennan.dungeontrain.platform.event.DtCommandRegistrationCallback;
import games.brennan.dungeontrain.platform.event.DtEvents;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Thin NeoForge → {@code DtEvents} bridge for command-related game-bus events.
 * Auto-registered via {@link EventBusSubscriber}; subscribes each NeoForge event
 * once and fires the matching {@code DtEvents} field. No logic — exact semantic
 * passthrough.
 */
@EventBusSubscriber(modid = DtCore.MOD_ID)
public final class NeoForgeCommandBridge {

    private NeoForgeCommandBridge() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        for (DtCommandRegistrationCallback cb : DtEvents.COMMAND_REGISTRATION.listeners()) {
            cb.register(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection());
        }
    }

    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        for (DtCommandCallback cb : DtEvents.COMMAND_EXEC.listeners()) {
            if (cb.onCommand(event.getParseResults())) {
                event.setCanceled(true);
                return; // first cancel wins — matches NeoForge cancellable-event short-circuit
            }
        }
    }
}
