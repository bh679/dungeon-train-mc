package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.AisDataIntegrity;
import games.brennan.dungeontrain.cheat.CheatModIntegrity;
import games.brennan.dungeontrain.cheat.CommandAllowlist;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.cheat.WorldSettingsIntegrity;
import games.brennan.dungeontrain.compat.EnderChestLockBridge;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.ShowFreePlayConfirmPacket;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

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
 *   <li>{@link #holdDifficultyChange} — the pause-menu difficulty slider, routed here
 *       from {@code ServerGamePacketListenerImplDifficultyMixin} because it never goes
 *       through a command. Below-default difficulties are held and confirmed exactly
 *       like a cheat command; on confirm the change is applied.</li>
 *   <li>{@link PlayerEvent.PlayerLoggedInEvent} — a world joined directly in
 *       creative/spectator marks Free Play (nothing to back out of), as does one
 *       <em>created</em> with {@code keepInventory} on or below-default difficulty
 *       ({@link WorldSettingsIntegrity}); and the run-scoped effect is re-applied if
 *       already Free Play.</li>
 *   <li>{@link ServerTickEvent.Post} — a throttled world-settings backstop for the
 *       paths nothing intercepts ({@code /execute}, another mod, a datapack).</li>
 *   <li>{@link PlayerEvent.PlayerRespawnEvent} — re-applies the effect after a
 *       death clears it, while the run is still Free Play.</li>
 * </ul>
 *
 * @see RunIntegrity
 * @see CommandAllowlist
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class CheatDetectionEvents {

    /** Re-check cadence for the world-settings backstop, in server ticks (20 = 1 s). */
    private static final int SETTINGS_CHECK_PERIOD_TICKS = 20;

    private static int settingsTickCounter = 0;

    /** A tainting action held per-player while its Free Play confirmation is open. */
    private sealed interface Pending permits PendingCommand, PendingDifficulty {
        /** The soft cause phrase recorded when the player confirms. */
        Component cause();
        /** The short trigger label shown on the confirm screen. */
        Component label();
    }

    /** A non-allowlisted command, canceled and re-dispatched on confirm. */
    private record PendingCommand(String rawCommand, Component label) implements Pending {
        @Override
        public Component cause() {
            return Component.translatable("chat.dungeontrain.free_play.cause.command", label);
        }
    }

    /** A below-default difficulty change, canceled and applied on confirm. */
    private record PendingDifficulty(Difficulty difficulty, Component label) implements Pending {
        @Override
        public Component cause() {
            return WorldSettingsIntegrity.causeDifficulty(difficulty);
        }
    }

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
        Component label = Component.literal(CommandAllowlist.label(event.getParseResults()));
        hold(player, new PendingCommand(raw, label));
    }

    /**
     * The difficulty slider in the pause menu bypasses the command path entirely,
     * so {@code ServerGamePacketListenerImplDifficultyMixin} routes it here.
     * Dropping below {@link WorldSettingsIntegrity#DEFAULT_DIFFICULTY} gets the same
     * hold-and-confirm treatment as a cheat command; anything at or above it, or a
     * run that is already permanently cheated, passes straight through.
     *
     * @return true when the change was held (the caller must cancel it) — on confirm
     *         it is re-applied by {@link #onConfirmResponse}.
     */
    public static boolean holdDifficultyChange(ServerPlayer player, Difficulty difficulty) {
        if (!WorldSettingsIntegrity.isFreePlayDifficulty(difficulty)) return false;
        if (RunIntegrity.isPermanentlyCheated(player)) return false;

        if (AisDataIntegrity.isSessionFreePlay() || CheatModIntegrity.isSessionFreePlay()) {
            // Already visibly Free Play this session — nothing to confirm or back
            // out of. Record the permanent taint quietly and let the change through.
            RunIntegrity.markCheated(player, WorldSettingsIntegrity.causeDifficulty(difficulty));
            return false;
        }

        hold(player, new PendingDifficulty(difficulty, difficulty.getDisplayName()));
        return true;
    }

    /** Park a held action and ask the player to confirm Free Play first. */
    private static void hold(ServerPlayer player, Pending pending) {
        PENDING.put(player.getUUID(), pending);
        DungeonTrainNet.sendTo(player, new ShowFreePlayConfirmPacket(pending.label()));
    }

    /** Called from {@code FreePlayConfirmResponsePacket} on the server thread. */
    public static void onConfirmResponse(ServerPlayer player, boolean confirmed) {
        Pending pending = PENDING.remove(player.getUUID());
        if (pending == null) return;
        if (!confirmed) {
            // Backed out — the held action stayed canceled. Re-assert the true
            // difficulty on the client, whose options widget optimistically showed
            // the value it sent.
            if (pending instanceof PendingDifficulty) resyncDifficulty(player);
            return;
        }
        RunIntegrity.markCheated(player, pending.cause());
        // Lock the live Ender Chest onto the Free Play (creative) slot now, before
        // the held action runs — the legit chest is hidden the instant the run trips.
        EnderChestLockBridge.engage(player);
        MinecraftServer server = player.getServer();
        if (server == null) return;
        switch (pending) {
            // Re-run the held command. isCheated is now true, so onCommand won't re-gate it.
            case PendingCommand cmd ->
                server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), cmd.rawCommand());
            // Apply the held difficulty. The mixin won't re-gate it — the run is now cheated.
            case PendingDifficulty diff -> server.setDifficulty(diff.difficulty(), false);
        }
    }

    /** Push the server's actual difficulty back to one player's client. */
    private static void resyncDifficulty(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        player.connection.send(new ClientboundChangeDifficultyPacket(
            server.getWorldData().getDifficulty(), server.getWorldData().isDifficultyLocked()));
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
        // Same for a world *created* with keepInventory on or below-default
        // difficulty (the world-creation Game Rules / difficulty screens never
        // reach the command or packet gates).
        WorldSettingsIntegrity.sweep(player.getServer());
    }

    /**
     * The world-settings backstop, throttled to ~1&nbsp;s. The difficulty slider is
     * intercepted with a confirm prompt and {@code /gamerule} goes through the
     * command gate, so this exists for the paths nothing intercepts: {@code /execute},
     * another mod, a datapack, a LAN "allow cheats" toggle. Two field reads per
     * second, and a no-op once the run is already tainted.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++settingsTickCounter < SETTINGS_CHECK_PERIOD_TICKS) return;
        settingsTickCounter = 0;
        WorldSettingsIntegrity.sweep(event.getServer());
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
