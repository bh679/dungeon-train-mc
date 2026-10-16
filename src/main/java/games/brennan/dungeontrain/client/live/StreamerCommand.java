package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * {@code /streamer} in game: asks the Live chat who is streaming. The relay answers for the whole room
 * (website, Discord, Twitch and every connected game) as {@code [Live] Dungeon Train: Streamer: …}, and
 * throttles it itself. Typed anywhere else it is a vanilla command, so it is registered client side and
 * sent through {@link LiveChatBridge#sendCommand}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class StreamerCommand {

    static final String COMMAND = "streamer";
    static final String OFF_KEY = "chat.dungeontrain.live_chat.streamer_off";

    private StreamerCommand() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(COMMAND).executes(ctx -> ask()));
    }

    private static int ask() {
        if (LiveChatBridge.sendCommand("/" + COMMAND)) return 1;
        Minecraft.getInstance().gui.getChat().addMessage(Component.translatable(OFF_KEY).withStyle(ChatFormatting.GRAY));
        return 0;
    }
}
