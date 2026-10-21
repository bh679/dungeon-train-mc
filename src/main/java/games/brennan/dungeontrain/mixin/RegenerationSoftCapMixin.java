package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.difficulty.EffectSoftCaps;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Regeneration V and above follows {@link EffectSoftCaps#regenHealPerSecond} instead of vanilla.
 *
 * <p>Vanilla heals 1 HP every {@code 50 >> amplifier} ticks: level VI already heals every tick
 * (20 HP/s), and because Java masks shift counts to 5 bits, amplifier 32 wraps back to level I's
 * pace. Deep-train mobs roll far past both. From level V up this heals a fraction of an HP every
 * tick so the rate climbs towards {@link EffectSoftCaps#REGEN_CEILING_HP_PER_SEC} without
 * reaching it. Levels I-IV are untouched.</p>
 */
@Mixin(targets = "net.minecraft.world.effect.RegenerationMobEffect")
public abstract class RegenerationSoftCapMixin {

    @Inject(method = "shouldApplyEffectTickThisTick", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$softCapEveryTick(int duration, int amplifier, CallbackInfoReturnable<Boolean> cir) {
        if (EffectSoftCaps.isSoftCapped(amplifier)) cir.setReturnValue(true);
    }

    @Inject(method = "applyEffectTick", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$softCapHeal(LivingEntity entity, int amplifier, CallbackInfoReturnable<Boolean> cir) {
        if (!EffectSoftCaps.isSoftCapped(amplifier)) return;
        if (entity.getHealth() < entity.getMaxHealth()) {
            entity.heal((float) (EffectSoftCaps.regenHealPerSecond(amplifier) / 20.0));
        }
        cir.setReturnValue(true);
    }
}
