package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.perks.effects.alchemist.BrewmasterMath;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Brewmaster's stronger potions keep their potion and add custom copies of its effects at a higher level.
 * Here an effect that appears both ways is folded into its strongest copy ({@link BrewmasterMath#merge}),
 * so the tooltip reads "Strength II" once and drinking, splashes, lingering clouds and tipped arrows apply
 * only the stronger one. Contents without custom effects, or without a repeat, are untouched.
 */
@Mixin(PotionContents.class)
public abstract class PotionContentsMergeMixin {

    @Shadow @Final private Optional<Holder<Potion>> potion;
    @Shadow @Final private List<MobEffectInstance> customEffects;

    @Unique
    private List<MobEffectInstance> dungeontrain$merged() {
        if (customEffects.isEmpty() || potion.isEmpty()) return null;
        List<MobEffectInstance> all = new ArrayList<>(potion.get().value().getEffects());
        all.addAll(customEffects);
        List<MobEffectInstance> merged = BrewmasterMath.merge(all);
        return merged == all ? null : merged;
    }

    @Inject(method = "getAllEffects", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$mergeAll(CallbackInfoReturnable<Iterable<MobEffectInstance>> cir) {
        List<MobEffectInstance> merged = dungeontrain$merged();
        if (merged != null) cir.setReturnValue(merged);
    }

    @Inject(method = "forEachEffect", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$mergeEach(Consumer<MobEffectInstance> action, CallbackInfo ci) {
        List<MobEffectInstance> merged = dungeontrain$merged();
        if (merged == null) return;
        for (MobEffectInstance e : merged) action.accept(new MobEffectInstance(e));
        ci.cancel();
    }
}
