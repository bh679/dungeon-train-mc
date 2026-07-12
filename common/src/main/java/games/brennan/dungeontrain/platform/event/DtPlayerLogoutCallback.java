package games.brennan.dungeontrain.platform.event;

import net.minecraft.world.entity.player.Player;

/**
 * Loader-neutral form of NeoForge's {@code PlayerEvent.PlayerLoggedOutEvent}:
 * fired on the server thread when a player disconnects. Not cancellable; exposes
 * the {@link Player}. All DT handlers were NORMAL priority.
 *
 * @param player the leaving player (matches {@code PlayerLoggedOutEvent.getEntity()})
 */
@FunctionalInterface
public interface DtPlayerLogoutCallback {
    void onPlayerLoggedOut(Player player);
}
