package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.EditorContentIntegrity;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.compat.EnderChestLockBridge;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.ShowCustomContentPromptPacket;
import games.brennan.dungeontrain.world.CustomContentChoice;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
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
 * <p><b>The fallback, not the main path.</b> Singleplayer normally answers this
 * before the world exists — {@code CustomContentGate} asks on New World and on
 * reboard, and {@code EditorContentIntegrity.onOverworldLoad} commits the answer
 * ahead of worldgen — so a world reaching login still {@link CustomContentChoice#UNSET}
 * means one of the routes that can't be asked earlier: a multiplayer join, a
 * world made through the vanilla world list, or a save from before the gate
 * existed. Those still get the question here, where the honest thing to say is
 * that the content is already loading.</p>
 *
 * <p>Runs at {@link EventPriority#LOW} so the Free Play notices in
 * {@code CheatDetectionEvents.onLogin} (HIGHEST) have already been sent — the
 * prompt is the last thing the player sees, and it reads as a response to those
 * lines rather than competing with them.</p>
 *
 * <p>The prompt is skipped entirely when the run is already Free Play for some other reason
 * ({@link RunIntegrity#isFreePlayApartFromCustomContent}) — creative mode, a cheat mod, a retuned
 * config. Its question is a trade ("keep your designs and run as Free Play, or drop them and keep
 * your stats") and that trade is already spent, so the content simply stays on and chat says which
 * packages are loading. The world choice stays {@link CustomContentChoice#UNSET}: it is permanent
 * and world-wide, so a clean run still gets to answer it properly.</p>
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
    private static final String COMMAND_ON = "/customcontent on";
    private static final String COMMAND_OFF = "/customcontent off";

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

        // The run IS Free Play from this tick — the content is loading whether or not anyone has
        // answered yet — so the status effect goes on immediately either way. That is the honest,
        // non-silent part, and it costs no chat line.
        RunIntegrity.applyFreePlayEffect(player);

        if (!choice.isAnswered()) {
            if (RunIntegrity.isFreePlayApartFromCustomContent(player)) {
                // The prompt's whole question is "keep your designs and run as Free Play, or drop
                // them and keep your stats". This run is Free Play regardless — creative mode, a
                // cheat mod, a retuned config — so there is nothing left to trade and the modal is
                // just in the way. Keep the content (it is already loading) and say so in chat.
                //
                // The world choice stays UNSET on purpose: it is permanent and world-wide, and the
                // player hasn't answered it. Opening this world in a clean run still asks properly.
                LOGGER.info("[DungeonTrain] Skipping the custom content prompt for {} — run is "
                        + "already Free Play for another reason; content ({}) stays on, choice stays UNSET.",
                    player.getName().getString(), packageSummary());
                sendPackagesNotice(player);
                return;
            }
            // Un-answered: the window is the whole message. No chat lines here — the player gets
            // the popup and hears from chat only once they've chosen, exactly the order the normal
            // Free Play flow uses (FreePlayConfirmScreen first, notice after).
            LOGGER.info("[DungeonTrain] Asking {} about custom content ({}) — world choice is UNSET.",
                player.getName().getString(), packageSummary());
            DungeonTrainNet.sendTo(player,
                new ShowCustomContentPromptPacket(packageSummary()));
            return;
        }
        // Already answered "keep it" on a previous join — re-state why the run is Free Play, the
        // same way the AIS and cheat-mod taints re-explain themselves at every login.
        sendFreePlayNotice(player);
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
        onChoiceApplied(player.getServer());

        // Now — and only now — chat explains itself: keeping the content means Free Play, so say
        // what that costs. Turning it off changes the world for everybody, so everybody hears.
        if (keepContent) {
            sendFreePlayNotice(player);
        } else {
            broadcast(player, Component.translatable("chat.dungeontrain.custom_content.now_disabled",
                    player.getName().getString())
                .withStyle(ChatFormatting.GRAY));
        }
    }

    /**
     * Settle everyone's Free Play state after this world's content choice changed — from the
     * prompt or from {@code /customcontent}.
     *
     * <p>Turning the content off can end a session-only taint
     * ({@link games.brennan.dungeontrain.cheat.EditorContentIntegrity#isSessionFreePlay}), and the
     * badge is an infinite saved effect that nothing else takes off — so a player whose run just
     * stopped being Free Play would otherwise wear it for the life of the save. A run tainted
     * <em>permanently</em> is never handed back here: the permanent taint has no way out, by
     * design, whatever its cause.</p>
     *
     * <p>Per-world decision, so it runs for every online player, not just whoever answered.</p>
     */
    public static void onChoiceApplied(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            RunIntegrity.reconcileFreePlayEffect(p);
            // The Ender Chest slot is derived from isCheated, but the live chest only follows on a
            // refresh — the same call CheatDetectionEvents makes when a run trips Free Play, run
            // here so a run that just left a session-only taint gets its legit chest back rather
            // than the creative one it was locked onto. No-op when the slot is unchanged.
            EnderChestLockBridge.engage(p);
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
        sendPackagesNotice(player);
    }

    /**
     * Which packages are loading, and the one-click way to turn them off — without the Free Play
     * title. Used on its own when the run is Free Play for some other reason and has already been
     * titled for it; repeating the banner would just say the same thing twice.
     */
    private static void sendPackagesNotice(ServerPlayer player) {
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
