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
 * Friendliness perk: a PlayerMob feels warmer toward a player holding the perk, each rank closing
 * part of the gap to the ledger's top ({@link Friendliness#feelingFor}). {@code feelingToward} is
 * the one funnel every reaction read goes through ({@code reactionToward}, the nearest-by-reaction
 * scans), so the raise never touches the ledger the mob saves; dying and losing the rank cools the
 * mob back to what it actually recorded.
 */
@Mixin(value = PlayerMobEntity.class, remap = false)
public abstract class PlayerMobFeelingMixin {

    @Inject(method = "feelingToward", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$perkFeeling(LivingEntity target, CallbackInfoReturnable<Float> cir) {
        int rank = Friendliness.rankOf(target);
        if (rank <= 0) return;
        float recorded = Math.max(FeelingLedger.MIN, cir.getReturnValueF());
        cir.setReturnValue(Math.min(FeelingLedger.MAX, Friendliness.feelingFor(recorded, rank, FeelingLedger.MAX)));
    }
}
