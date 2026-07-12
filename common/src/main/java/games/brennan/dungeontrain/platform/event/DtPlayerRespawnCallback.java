package games.brennan.dungeontrain.platform.event;

import net.minecraft.world.entity.player.Player;

/**
 * Loader-neutral form of NeoForge's {@code PlayerEvent.PlayerRespawnEvent}: fired
 * on the server thread when a player respawns. Not cancellable; exposes the
 * {@link Player} and the end-portal flag. All DT handlers were NORMAL priority.
 *
 * @param player        the respawning player (matches {@code getEntity()})
 * @param endConquered  {@code true} when respawn is the End→overworld portal exit
 *                      (matches {@code PlayerRespawnEvent.isEndConquered()})
 */
@FunctionalInterface
public interface DtPlayerRespawnCallback {
    void onPlayerRespawn(Player player, boolean endConquered);
}
