package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.client.NetworkConsentScreen;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.chat.ChatClick;
import games.brennan.dungeontrain.client.ChatClickActions;
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
import org.slf4j.Logger;

import java.util.List;

/**
 * Chat lines' ways into Dungeon Train's Options screen. {@link #pathLink} is a chat-click action
 * ({@link #OPTIONS_ACTION}) that opens the screen on a setting's tab with its row in view and
 * highlighted. The "Live Eye needs the internet" line's link opens the consent card the same way
 * ({@link #CONSENT_ACTION}), so there is nothing to type. {@code /dungeontrain-options} opens the
 * screen on its General tab.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveOptionsCommand {

    /** Chat-click action that reopens the internet consent card; the server builds the link ({@code LiveFeedEvents}). */
    public static final String CONSENT_ACTION = "consent";
    /** Chat-click action that opens Options at a setting: {@code options/<TAB>/<ROW>}. */
    public static final String OPTIONS_ACTION = "options";

    private static final Logger LOGGER = LogUtils.getLogger();

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

    /**
     * The "Live Eye needs the internet" line's link ({@link ChatClick} action {@link #CONSENT_ACTION}):
     * the consent card, as Options → Content opens it. Wired from {@code DungeonTrainClient.clientSetup}.
     */
    public static void registerClicks() {
        ChatClickActions.register(CONSENT_ACTION, args -> {
            Minecraft mc = Minecraft.getInstance();
            DungeonTrain.markConsentCardReopened();
            mc.setScreen(new NetworkConsentScreen(null));
        });
        ChatClickActions.register(OPTIONS_ACTION, LiveOptionsCommand::openAt);
    }

    /** {@code args} is the tab then the row. A name this client doesn't know still opens what it can. */
    private static void openAt(List<String> args) {
        ClientOptionsTab tab = args.isEmpty() ? null : ClientOptionsTab.tabNamed(args.get(0));
        ClientOptionsTab.Row row = args.size() < 2 ? null : ClientOptionsTab.rowNamed(args.get(1));
        if (tab == null || row == null) {
            LOGGER.warn("[DungeonTrain] Options link to unknown setting {}", args);
        }
        if (tab != null) {
            ClientOptionsTab.select(tab);
        }
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(tab != null && row != null
            ? new DungeonTrainClientOptionsScreen(null, row)
            : new DungeonTrainClientOptionsScreen(null));
    }

    /** "Options / Dungeon Train / Stream / …" as an underlined link that opens {@code tab} at {@code row}. */
    public static MutableComponent pathLink(String pathKey, ClientOptionsTab tab, ClientOptionsTab.Row row) {
        return Component.translatable(pathKey).withStyle(style -> style
            .withUnderlined(true)
            .withColor(ChatFormatting.AQUA)
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, linkValue(tab, row))));
    }

    /** The click value of a link to {@code row} on {@code tab}. */
    static String linkValue(ClientOptionsTab tab, ClientOptionsTab.Row row) {
        return ChatClick.value(OPTIONS_ACTION, tab.name(), row.name());
    }
}
