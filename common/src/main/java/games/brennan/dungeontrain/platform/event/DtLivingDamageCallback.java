package games.brennan.dungeontrain.platform.event;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * Loader-neutral form of NeoForge's {@code LivingDamageEvent.Post}: fired on the
 * server thread AFTER damage is applied. The {@code Post} phase is read-only (not
 * cancellable, not mutable — mitigation already happened), so this callback is a
 * plain observer carrying the final post-mitigation damage. Both DT handlers were
 * NORMAL priority.
 *
 * @param entity    the entity that was hurt (matches {@code getEntity()})
 * @param source    the damage source (matches {@code getSource()})
 * @param newDamage the post-mitigation damage dealt (matches {@code Post.getNewDamage()})
 */
@FunctionalInterface
public interface DtLivingDamageCallback {
    void onLivingDamage(LivingEntity entity, DamageSource source, float newDamage);
}
