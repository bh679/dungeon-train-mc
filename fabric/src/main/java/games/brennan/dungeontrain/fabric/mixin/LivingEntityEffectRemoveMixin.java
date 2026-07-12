package games.brennan.dungeontrain.fabric.mixin;

import games.brennan.dungeontrain.platform.event.DtEvents;
import games.brennan.dungeontrain.platform.event.DtMobEffectRemoveCallback;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gap-filler for {@code DtEvents.MOB_EFFECT_REMOVE} (NeoForge {@code MobEffectEvent.Remove},
 * cancellable). Fabric has no such event; fires at the HEAD of
 * {@code LivingEntity.removeEffectNoUpdate} and, on the first handler that returns
 * {@code true}, cancels the removal by returning {@code null} — matching the NeoForge
 * bridge's stop-on-first-cancel (DT uses this to keep the permanent Free Play effect).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityEffectRemoveMixin {

    @Inject(method = "removeEffectNoUpdate", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$effectRemove(Holder<MobEffect> effect,
                                           CallbackInfoReturnable<MobEffectInstance> cir) {
        if (DtEvents.MOB_EFFECT_REMOVE.isEmpty()) {
            return;
        }
        LivingEntity self = (LivingEntity) (Object) this;
        for (DtMobEffectRemoveCallback cb : DtEvents.MOB_EFFECT_REMOVE.listeners()) {
            if (cb.onEffectRemove(self, effect)) {
                cir.setReturnValue(null); // cancel removal
                return;
            }
        }
    }
}
