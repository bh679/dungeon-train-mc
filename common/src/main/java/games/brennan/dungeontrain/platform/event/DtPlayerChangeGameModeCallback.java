package games.brennan.dungeontrain.platform.event;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

/**
 * Loader-neutral form of NeoForge's {@code PlayerEvent.PlayerChangeGameModeEvent}:
 * fired on the server thread when a player's game mode changes. The NeoForge event
 * is cancellable and lets a listener override the new mode; DT's sole handler does
 * neither (it only observes to flag Free Play), so this callback is {@code void}
 * and carries the incoming new mode read-only. All DT handlers were NORMAL priority.
 *
 * @param player      the player (matches {@code getEntity()})
 * @param newGameMode the mode being switched to (matches {@code getNewGameMode()})
 */
@FunctionalInterface
public interface DtPlayerChangeGameModeCallback {
    void onChangeGameMode(Player player, GameType newGameMode);
}
