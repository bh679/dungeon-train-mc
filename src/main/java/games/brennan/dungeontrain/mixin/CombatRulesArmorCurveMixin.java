package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.combat.ArmorReductionCurve;
import net.minecraft.world.damagesource.CombatRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lifts vanilla's 20-point armor ceiling (80% reduction) in favour of {@link ArmorReductionCurve},
 * which keeps rewarding armor and toughness but never blocks 90% or more.
 *
 * <p>Wraps the first {@code Mth.clamp(armor - damage / f, armor * 0.2, 20)} in
 * {@code getDamageAfterAbsorb} and hands back the curve's reduction as armor points
 * ({@code reduction * 25}), so vanilla's own {@code / 25}, Breach and final multiply run unchanged.
 * The float parameters are {@code (damage, armor, toughness)} in that order.</p>
 */
@Mixin(CombatRules.class)
public abstract class CombatRulesArmorCurveMixin {

    @WrapOperation(method = "getDamageAfterAbsorb",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(FFF)F", ordinal = 0))
    private static float dungeontrain$armorCurve(float value, float min, float max, Operation<Float> original,
                                                 @Local(argsOnly = true, ordinal = 0) float damage,
                                                 @Local(argsOnly = true, ordinal = 1) float armor,
                                                 @Local(argsOnly = true, ordinal = 2) float toughness) {
        return ArmorReductionCurve.reduction(armor, toughness, damage) * 25.0f;
    }
}
