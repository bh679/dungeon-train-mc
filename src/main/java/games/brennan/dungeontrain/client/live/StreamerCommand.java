package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * {@code /streamer} in game: asks the Live chat who is streaming. The relay answers for the whole room
 * (website, Discord, Twitch and every connected game) as {@code [Live] Dungeon Train: Streamer: …}, and
 * throttles it itself. Typed anywhere else it is a vanilla command, so it is registered client side and
 * sent through {@link LiveChatBridge#sendCommand}. Out of Live chat it does nothing.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class StreamerCommand {

    static final String COMMAND = "streamer";

    private StreamerCommand() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(COMMAND)
            .executes(ctx -> LiveChatBridge.sendCommand("/" + COMMAND) ? 1 : 0));
    }
}
