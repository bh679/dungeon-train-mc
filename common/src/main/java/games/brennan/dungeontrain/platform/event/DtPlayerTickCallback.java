package games.brennan.dungeontrain.platform.event;

import net.minecraft.world.entity.player.Player;

/**
 * Loader-neutral form of NeoForge's {@code PlayerTickEvent.Post}: fired after
 * each player ticks (client and server; handlers self-filter). Not cancellable;
 * exposes the {@link Player}. All DT handlers were NORMAL priority.
 *
 * @param player the player that just ticked (matches {@code PlayerTickEvent.getEntity()})
 */
@FunctionalInterface
public interface DtPlayerTickCallback {
    void onPlayerTick(Player player);
}
