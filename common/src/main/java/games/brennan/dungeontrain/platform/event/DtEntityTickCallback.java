package games.brennan.dungeontrain.platform.event;

import net.minecraft.world.entity.Entity;

/**
 * Loader-neutral form of NeoForge's {@code EntityTickEvent.Pre}: fired before
 * each entity ticks. NeoForge's {@code Pre} is technically cancellable, but DT's
 * sole handler ({@code NetherBandZombificationGuard}) never cancels — it only
 * flips a synced zombification-immunity flag — so this callback is {@code void}
 * (no cancel path). If a future handler needs to cancel, promote to a boolean
 * (cancel) callback and wire {@code event.setCanceled} in the bridge. All DT
 * handlers were NORMAL priority.
 *
 * @param entity the entity about to tick (matches {@code EntityTickEvent.getEntity()})
 */
@FunctionalInterface
public interface DtEntityTickCallback {
    void onEntityTick(Entity entity);
}
