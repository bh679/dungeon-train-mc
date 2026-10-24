package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.perks.tier1.Friendliness;
import games.brennan.playermob.entity.FeelingLedger;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Friendliness perk: a PlayerMob feels {@link Friendliness#feelingBonus} warmer toward a player
 * holding the perk. {@code feelingToward} is the one funnel every reaction read goes through
 * ({@code reactionToward}, the nearest-by-reaction scans), so the bonus never touches the ledger
 * the mob saves; dying and losing the rank cools the mob back to what it actually recorded.
 */
@Mixin(value = PlayerMobEntity.class, remap = false)
public abstract class PlayerMobFeelingMixin {

    @Inject(method = "feelingToward", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$perkFeeling(LivingEntity target, CallbackInfoReturnable<Float> cir) {
        float bonus = Friendliness.feelingBonusFor(target);
        if (bonus <= 0f) return;
        float raised = Math.min(FeelingLedger.MAX, Math.max(FeelingLedger.MIN, cir.getReturnValueF() + bonus));
        cir.setReturnValue(raised);
    }
}
