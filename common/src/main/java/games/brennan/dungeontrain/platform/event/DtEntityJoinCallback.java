package games.brennan.dungeontrain.platform.event;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Loader-neutral form of NeoForge's {@code EntityJoinLevelEvent} (CANCELLABLE):
 * fired when an entity is about to be added to a level, on both client and server
 * (handlers self-filter with {@code instanceof ServerLevel}). Returning
 * {@code true} cancels the join — equivalent to {@code event.setCanceled(true)},
 * which discards the entity so it is never added.
 *
 * <p><b>Cancellation contract (preserved exactly):</b> none of DT's seven handlers
 * registered {@code receiveCanceled=true}, so on the old bus, once any listener
 * cancelled, the remaining default-priority listeners were skipped.
 * {@code NeoForgeEntityJoinBridge} reproduces this: it invokes DT's callbacks in
 * registration order and stops at the FIRST that returns {@code true}, mapping
 * that to {@code event.setCanceled(true)} so other mods and vanilla see the
 * cancellation too. (All DT handlers are NORMAL priority; if a higher-priority
 * other-mod listener cancels first, NeoForge never calls the bridge — matching a
 * non-{@code receiveCanceled} listener.)</p>
 *
 * @param entity         the joining entity (matches {@code getEntity()})
 * @param level          the level being joined (matches {@code getLevel()})
 * @param loadedFromDisk whether the entity is being loaded from disk vs freshly
 *                       spawned (matches {@code EntityJoinLevelEvent.loadedFromDisk()})
 * @return {@code true} to cancel the join, {@code false} to allow it
 */
@FunctionalInterface
public interface DtEntityJoinCallback {
    boolean onEntityJoin(Entity entity, Level level, boolean loadedFromDisk);
}
