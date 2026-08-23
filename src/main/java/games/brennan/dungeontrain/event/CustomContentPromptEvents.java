package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.EditorContentIntegrity;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.ShowCustomContentPromptPacket;
import games.brennan.dungeontrain.world.CustomContentChoice;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

/**
 * The join-time half of the custom Train Editor content feature: ask once per
 * world whether to keep the content (and run in Free Play) or disable it, and
 * apply the answer.
 *
 * <p>Runs at {@link EventPriority#LOW} so the Free Play notices in
 * {@code CheatDetectionEvents.onLogin} (HIGHEST) have already been sent — the
 * prompt is the last thing the player sees, and it reads as a response to those
 * lines rather than competing with them.</p>
 *
 * <p>The decision is per-world, not per-player. On a shared server every player
 * who joins while the world is {@link CustomContentChoice#UNSET} is prompted,
 * the first answer to arrive wins, and any later answer is reported back rather
 * than applied — otherwise two players answering differently would flip the
 * content back and forth mid-session.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class CustomContentPromptEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The one-click way back, offered in every notice this class sends. */
    private static final String COMMAND_ON = "/dt customcontent on";
    private static final String COMMAND_OFF = "/dt customcontent off";

    private CustomContentPromptEvents() {}

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!EditorContentIntegrity.hasCustomContent()) return;

        CustomContentChoice choice = EditorContentIntegrity.choice();
        if (choice.suppressesContent()) {
            // Content is installed but this world has turned it off — say so, quietly, so a player
            // wondering where their builds went isn't left guessing.
            LOGGER.info("[DungeonTrain] Custom content is disabled for this world; {} joined clean.",
                player.getName().getString());
            player.sendSystemMessage(Component.translatable("chat.dungeontrain.custom_content.disabled")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(" "))
                .append(fixLink("chat.dungeontrain.custom_content.enable_link", COMMAND_ON)));
            return;
        }

        // Content is loading, so the run is Free Play from this tick — whether or not anyone has
        // answered the prompt yet. Say so FIRST, unconditionally: an UNSET world that only sent the
        // prompt would leave a player whose prompt didn't surface silently tainted, which is worse
        // than not having the feature. Same contract as the AIS and cheat-mod login notices.
        sendFreePlayNotice(player);

        if (!choice.isAnswered()) {
            LOGGER.info("[DungeonTrain] Asking {} about custom content ({}) — world choice is UNSET.",
                player.getName().getString(), packageSummary());
            DungeonTrainNet.sendTo(player,
                new ShowCustomContentPromptPacket(packageSummary()));
        }
    }

    /**
     * Server-side handler for {@code CustomContentChoicePacket}. Idempotent per world: once the
     * choice is set, a second answer only reports what is already in force.
     */
    public static void onChoice(ServerPlayer player, boolean keepContent) {
        LOGGER.info("[DungeonTrain] {} answered the custom content prompt: keepContent={}",
            player.getName().getString(), keepContent);
        if (!EditorContentIntegrity.hasCustomContent()) return; // content vanished mid-prompt

        CustomContentChoice existing = EditorContentIntegrity.choice();
        if (existing.isAnswered()) {
            player.sendSystemMessage(Component.translatable(
                    existing.suppressesContent()
                        ? "chat.dungeontrain.custom_content.already_disabled"
                        : "chat.dungeontrain.custom_content.already_kept")
                .withStyle(ChatFormatting.GRAY));
            return;
        }

        CustomContentChoice choice = keepContent ? CustomContentChoice.ALLOW : CustomContentChoice.DISABLE;
        EditorContentIntegrity.setWorldChoice(player.getServer(), choice);

        // Keeping the content needs no further word — the Free Play notice already went out at
        // login, and the run simply carries on. Turning it off changes the world for everybody, so
        // everybody hears about it.
        if (!keepContent) {
            broadcast(player, Component.translatable("chat.dungeontrain.custom_content.now_disabled",
                    player.getName().getString())
                .withStyle(ChatFormatting.GRAY));
        }
    }

    /**
     * The Free Play explanation for an active-content world: the shared notice
     * ({@link RunIntegrity#sendFreePlayNotice}) plus which packages are responsible and the
     * command that turns them off. Same shape as the AIS notice in {@code CheatDetectionEvents}.
     */
    private static void sendFreePlayNotice(ServerPlayer player) {
        RunIntegrity.applyFreePlayEffect(player);
        RunIntegrity.sendFreePlayNotice(player,
            Component.translatable("chat.dungeontrain.free_play.cause.custom_content"));
        player.sendSystemMessage(Component.translatable(
                "chat.dungeontrain.custom_content.packages", packageSummary())
            .withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(fixLink("chat.dungeontrain.custom_content.disable_link", COMMAND_OFF));
    }

    /** A clickable, underlined aqua command line — the affordance {@code /fixaisconfig} established. */
    private static Component fixLink(String translationKey, String command) {
        return Component.translatable(translationKey)
            .withStyle(style -> style
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
    }

    /** Comma-joined names of the packages holding content, for the prompt and the notices. */
    public static String packageSummary() {
        return String.join(", ", EditorContentIntegrity.contentPackageNames());
    }

    /** Tell everyone — the flip changes the world for all of them, not just whoever clicked. */
    private static void broadcast(ServerPlayer origin, Component message) {
        if (origin.getServer() == null) {
            origin.sendSystemMessage(message);
            return;
        }
        for (ServerPlayer p : origin.getServer().getPlayerList().getPlayers()) {
            p.sendSystemMessage(message);
        }
    }
}
