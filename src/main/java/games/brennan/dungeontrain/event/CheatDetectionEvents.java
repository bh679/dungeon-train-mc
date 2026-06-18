package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.CommandAllowlist;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Detects cheating and marks the player's run cheated via {@link RunIntegrity}.
 *
 * <p>Three triggers, all server-side:
 * <ul>
 *   <li>{@link PlayerEvent.PlayerChangeGameModeEvent} — switching to
 *       creative/spectator. Also catches the cinematographer camera, which
 *       enters spectator under the hood ({@code CinematographerService}).</li>
 *   <li>{@link PlayerEvent.PlayerLoggedInEvent} (HIGHEST, so the flag is set
 *       before {@link AchievementEvents}' default-priority sidecar absorb/replay
 *       reads it) — a world created directly in creative/spectator never fires a
 *       change event.</li>
 *   <li>{@link CommandEvent} — any player-run command not on the
 *       {@link CommandAllowlist}.</li>
 * </ul>
 *
 * @see RunIntegrity
 * @see CommandAllowlist
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class CheatDetectionEvents {

    private CheatDetectionEvents() {}

    @SubscribeEvent
    public static void onChangeGameMode(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        markIfCheatMode(player, event.getNewGameMode());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        markIfCheatMode(player, player.gameMode.getGameModeForPlayer());
    }

    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        CommandSourceStack source = event.getParseResults().getContext().getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) return; // console / command block / function — not a player cheating
        if (CommandAllowlist.taints(event.getParseResults())) {
            RunIntegrity.markCheated(player, Component.translatable(
                "chat.dungeontrain.cheat.cause.command",
                CommandAllowlist.label(event.getParseResults())));
        }
    }

    private static void markIfCheatMode(ServerPlayer player, GameType mode) {
        if (mode == GameType.CREATIVE || mode == GameType.SPECTATOR) {
            RunIntegrity.markCheated(player, Component.translatable(
                "chat.dungeontrain.cheat.cause.gamemode", mode.getLongDisplayName()));
        }
    }
}
