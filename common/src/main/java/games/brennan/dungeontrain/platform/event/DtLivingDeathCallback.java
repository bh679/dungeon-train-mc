package games.brennan.dungeontrain.platform.event;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * Loader-neutral form of NeoForge's {@code LivingDeathEvent}: fired on the server
 * thread when a living entity dies. NeoForge's event is cancellable, but NO DT
 * handler cancels it, so this callback is {@code void}. It carries the current
 * {@code isCanceled()} flag read-only because one handler
 * ({@code RunStatsEvents.onPlayerDeath}) short-circuits on it.
 *
 * <p><b>Priority / cancellation contract (preserved):</b> seven handlers were
 * NORMAL and one ({@code RunStatsEvents.onPlayerDeath}) was LOW; none used
 * {@code receiveCanceled}. {@code NeoForgeLivingBridge} subscribes NORMAL and LOW
 * separately, both non-{@code receiveCanceled}, so if another mod cancels the
 * death at a higher priority NeoForge skips the bridge — identical to today (and
 * {@code isCanceled} is therefore false whenever DT's handlers run).</p>
 *
 * @param entity   the dying entity (matches {@code getEntity()})
 * @param source   the killing damage source (matches {@code getSource()})
 * @param canceled the event's current cancel flag (matches {@code isCanceled()})
 */
@FunctionalInterface
public interface DtLivingDeathCallback {
    void onDeath(LivingEntity entity, DamageSource source, boolean canceled);
}
