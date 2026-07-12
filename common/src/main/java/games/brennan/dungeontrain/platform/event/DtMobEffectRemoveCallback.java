package games.brennan.dungeontrain.platform.event;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;

/**
 * Loader-neutral form of NeoForge's {@code MobEffectEvent.Remove} (CANCELLABLE):
 * fired on the server thread when a mob effect is about to be removed from an
 * entity. Returning {@code true} cancels the removal — equivalent to
 * {@code event.setCanceled(true)} (the effect stays on the entity).
 *
 * <p>DT's sole handler ({@code CheatDetectionEvents.onEffectRemove}) was default
 * priority and did not use {@code receiveCanceled}. The bridge invokes it and, on
 * {@code true}, sets the real event cancelled so vanilla/other mods honour it.</p>
 *
 * @param entity the entity losing the effect (matches {@code getEntity()})
 * @param effect the effect being removed (matches {@code MobEffectEvent.Remove.getEffect()})
 * @return {@code true} to cancel the removal, {@code false} to allow it
 */
@FunctionalInterface
public interface DtMobEffectRemoveCallback {
    boolean onEffectRemove(LivingEntity entity, Holder<MobEffect> effect);
}
