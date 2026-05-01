package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.command.TrainCommand;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;

/**
 * Forge event-bus subscriber: hooks command registration.
 * Registered automatically via {@link EventBusSubscriber}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class CommandEvents {

    private CommandEvents() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        TrainCommand.register(event.getDispatcher(), event.getBuildContext());
    }
}
