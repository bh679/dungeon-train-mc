package games.brennan.dungeontrain.platform.event;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Loader-neutral form of NeoForge's {@code AttackEntityEvent}: fired on the server
 * thread when a player left-click attacks an entity. Cancellable upstream, but no
 * DT handler cancels — void callback carrying {@code isCanceled()} read-only. All
 * four DT handlers NORMAL.
 *
 * @param player   the attacking player (matches {@code getEntity()})
 * @param target   the attacked entity (matches {@code getTarget()})
 * @param canceled the event's current cancel flag (matches {@code isCanceled()})
 */
@FunctionalInterface
public interface DtAttackEntityCallback {
    void onAttackEntity(Player player, Entity target, boolean canceled);
}
