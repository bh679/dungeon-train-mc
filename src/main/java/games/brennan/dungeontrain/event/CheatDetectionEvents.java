package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.AisDataIntegrity;
import games.brennan.dungeontrain.cheat.CheatModIntegrity;
import games.brennan.dungeontrain.cheat.CommandAllowlist;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.compat.EnderChestLockBridge;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.ShowFreePlayConfirmPacket;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects actions that turn a run into <b>Free Play</b> and gates them behind a
 * confirmation the player can back out of (see
 * {@link games.brennan.dungeontrain.client.FreePlayConfirmScreen}).
 *
 * <p>Flow (server-side):
 * <ul>
 *   <li>{@link CommandEvent} — a player runs a non-allowlisted command (covers
 *       {@code /gamemode}, the F3+F4 switcher and {@code /dungeontrain
 *       cinematographer}, all of which route through commands, plus {@code /give}
 *       et al.). The command is <b>canceled</b> and held; a confirm prompt is sent.
 *       On confirm the run goes Free Play and the held command is re-dispatched
 *       (now {@code isCheated}, so it isn't re-gated); on cancel it's dropped.</li>
 *   <li>{@link PlayerEvent.PlayerChangeGameModeEvent} — a <b>non-cancelling
 *       backstop</b>: any creative/spectator switch that didn't come through the
 *       command path (a mod, {@code /execute}) just marks Free Play. It never
 *       cancels, so the cinematographer's internal {@code setGameMode} isn't
 *       broken; a command-driven switch is already {@code isCheated} by the time
 *       it runs, so this no-ops.</li>
 *   <li>{@link PlayerEvent.PlayerLoggedInEvent} — a world joined directly in
 *       creative/spectator marks Free Play (nothing to back out of); and the
 *       run-scoped effect is re-applied if already Free Play.</li>
 *   <li>{@link PlayerEvent.PlayerRespawnEvent} — re-applies the effect after a
 *       death clears it, while the run is still Free Play.</li>
 * </ul>
 *
 * @see RunIntegrity
 * @see CommandAllowlist
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class CheatDetectionEvents {

    /** A tainting command held per-player while its Free Play confirmation is open. */
    private record Pending(String rawCommand, String label) {}

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private CheatDetectionEvents() {}

    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        CommandSourceStack source = event.getParseResults().getContext().getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return;                 // console / command block / function
        if (RunIntegrity.isPermanentlyCheated(player)) return; // already recorded — let it run (incl. re-dispatch)
        if (!CommandAllowlist.taints(event.getParseResults())) return;

        if (AisDataIntegrity.isSessionFreePlay()) {
            // The session is already Free Play (AIS data changed) — there is
            // nothing to confirm or back out of. Just record the permanent taint
            // (quiet — markCheated skips the notice during a session taint) and
            // let the command run.
            RunIntegrity.markCheated(player, Component.translatable(
                "chat.dungeontrain.free_play.cause.command",
                CommandAllowlist.label(event.getParseResults())));
            return;
        }

        // Hold the command, ask the player to confirm Free Play first.
        event.setCanceled(true);
        String raw = event.getParseResults().getReader().getString();
        String label = CommandAllowlist.label(event.getParseResults());
        PENDING.put(player.getUUID(), new Pending(raw, label));
        DungeonTrainNet.sendTo(player, new ShowFreePlayConfirmPacket(label));
    }

    /** Called from {@code FreePlayConfirmResponsePacket} on the server thread. */
    public static void onConfirmResponse(ServerPlayer player, boolean confirmed) {
        Pending pending = PENDING.remove(player.getUUID());
        if (pending == null) return;
        if (!confirmed) return; // backed out — the command stayed canceled
        RunIntegrity.markCheated(player, Component.translatable(
            "chat.dungeontrain.free_play.cause.command", pending.label()));
        // Lock the live Ender Chest onto the Free Play (creative) slot now, before
        // the held command runs — the legit chest is hidden the instant the run trips.
        EnderChestLockBridge.engage(player);
        // Re-run the held command. isCheated is now true, so onCommand won't re-gate it.
        MinecraftServer server = player.getServer();
        if (server != null) {
            server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), pending.rawCommand());
        }
    }

    @SubscribeEvent
    public static void onChangeGameMode(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // Gate on the PERMANENT taint: during a session-only AIS taint a
        // creative/spectator switch must still be recorded permanently.
        if (RunIntegrity.isPermanentlyCheated(player)) return;
        markGameModeFreePlay(player, event.getNewGameMode());
        // If that just tripped Free Play (creative/spectator), lock the Ender Chest.
        // Runs before ECP's LOW-priority game-mode swap, while the old mode is still
        // active, so the legit chest is snapshotted back to its own slot first.
        if (RunIntegrity.isCheated(player)) EnderChestLockBridge.engage(player);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (AisDataIntegrity.isSessionFreePlay()) {
            // Session-only AIS taint: markCheated never runs in this path, so
            // apply the effect and explain WHY here, once per login — with the
            // exact changed settings and a one-click fix action.
            RunIntegrity.applyFreePlayEffect(player);
            RunIntegrity.sendFreePlayNotice(player,
                Component.translatable("chat.dungeontrain.free_play.cause.ais_data"));
            player.sendSystemMessage(Component.translatable(
                    "chat.dungeontrain.free_play.ais_changed",
                    String.join(", ", AisDataIntegrity.deviations()))
                .withStyle(ChatFormatting.GRAY));
            player.sendSystemMessage(Component.translatable("chat.dungeontrain.free_play.ais_fix")
                .withStyle(style -> style
                    .withColor(ChatFormatting.AQUA)
                    .withUnderlined(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/fixaisconfig"))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("/fixaisconfig")))));
        }
        if (CheatModIntegrity.isSessionFreePlay()) {
            // Session-only cheat-mod taint (parallel to the AIS block above): markCheated never
            // runs in this path, so apply the effect and explain WHY here, once per login — naming
            // the detected mod(s). No one-click fix: a mod can't be uninstalled from in-game.
            RunIntegrity.applyFreePlayEffect(player);
            RunIntegrity.sendFreePlayNotice(player,
                Component.translatable("chat.dungeontrain.free_play.cause.cheat_mod"));
            player.sendSystemMessage(Component.translatable(
                    "chat.dungeontrain.free_play.cheat_mods",
                    String.join(", ", CheatModIntegrity.detected()))
                .withStyle(ChatFormatting.GRAY));
            player.sendSystemMessage(Component.translatable("chat.dungeontrain.free_play.cheat_mods_fix")
                .withStyle(ChatFormatting.GRAY));
        }
        if (RunIntegrity.isPermanentlyCheated(player)) {
            RunIntegrity.applyFreePlayEffect(player); // re-apply across relog
            return;
        }
        // A world created/entered directly in creative/spectator — nothing to back
        // out of, so mark immediately (HIGHEST so the flag is set before
        // AchievementEvents' default-priority sidecar absorb/replay reads it).
        // During a session taint this still records the permanent flag, quietly.
        markGameModeFreePlay(player, player.gameMode.getGameModeForPlayer());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.isEndConquered()) return; // End → overworld portal, not a death
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (RunIntegrity.isCheated(player)) {
            RunIntegrity.applyFreePlayEffect(player); // death cleared the effect; re-apply
        }
    }

    /**
     * Free Play is permanent for the run — block its removal by {@code /effect
     * clear}, milk, or any cure while the run is still Free Play. (Once a new
     * world clears the flag the effect isn't present to remove.)
     */
    @SubscribeEvent
    public static void onEffectRemove(MobEffectEvent.Remove event) {
        if (!event.getEffect().is(ModMobEffects.FREE_PLAY.getId())) return;
        if (event.getEntity() instanceof ServerPlayer player && RunIntegrity.isCheated(player)) {
            event.setCanceled(true);
        }
    }

    private static void markGameModeFreePlay(ServerPlayer player, GameType mode) {
        if (mode == GameType.CREATIVE || mode == GameType.SPECTATOR) {
            RunIntegrity.markCheated(player, Component.translatable(
                "chat.dungeontrain.free_play.cause.gamemode", mode.getLongDisplayName()));
        }
    }
}
