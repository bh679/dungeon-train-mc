package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.command.EchoEncounterTestCommand;
import games.brennan.dungeontrain.command.TrainCommand;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.ModList;

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
        // Dev-only: a relay-free way to drive the remote-echo encounter journal end-to-end.
        // Never registered in production, and only when PlayerMob (whose types the command
        // references) is present.
        if (!FMLEnvironment.production && ModList.get().isLoaded("playermob")) {
            EchoEncounterTestCommand.register(event.getDispatcher());
        }
    }
}
