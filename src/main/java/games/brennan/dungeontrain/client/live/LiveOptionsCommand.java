package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientOptionsTab;
import games.brennan.dungeontrain.client.DungeonTrainClientOptionsScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * {@code /dungeontrain-options}: opens Dungeon Train's Options screen on its General tab. Exists so a
 * chat line can carry a clickable path to a setting — vanilla chat clicks can run a command and
 * nothing else — starting with the "Live-streaming is disabled" notice.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveOptionsCommand {

    public static final String COMMAND = "/dungeontrain-options";

    private LiveOptionsCommand() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dungeontrain-options").executes(ctx -> {
            Minecraft mc = Minecraft.getInstance();
            // The command runs from the chat screen; swap screens on the next tick so the chat input
            // closes cleanly first.
            mc.execute(() -> {
                ClientOptionsTab.select(ClientOptionsTab.GENERAL);
                mc.setScreen(new DungeonTrainClientOptionsScreen(null));
            });
            return 1;
        }));
    }

    /** "Options / Dungeon Train / General / …" as an underlined link that opens that page. */
    public static MutableComponent pathLink(String pathKey) {
        return Component.translatable(pathKey).withStyle(style -> style
            .withUnderlined(true)
            .withColor(ChatFormatting.AQUA)
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, COMMAND)));
    }
}
