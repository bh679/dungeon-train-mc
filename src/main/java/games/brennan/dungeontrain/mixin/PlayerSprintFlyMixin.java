package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.player.SprintFlyBoost;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scales creative sprint-flying by {@link SprintFlyBoost} ({@code /dt flyspeed}).
 *
 * <p>{@code Player.getFlyingSpeed} is the speed {@code LivingEntity.travel} feeds to
 * {@code moveRelative} — the horizontal input only. Vertical flight is added separately in
 * {@code LocalPlayer.aiStep} straight from {@code abilities.flyingSpeed}, so boosting here leaves
 * up/down at vanilla. Gated to flying + sprinting, so plain flight is untouched too. Client-side
 * only: that's where player movement is simulated.</p>
 */
@Mixin(Player.class)
public abstract class PlayerSprintFlyMixin {

    @Inject(method = "getFlyingSpeed", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$boostSprintFly(CallbackInfoReturnable<Float> cir) {
        Player self = (Player) (Object) this;
        if (!self.level().isClientSide()) return;
        if (!self.getAbilities().flying || self.isPassenger() || !self.isSprinting()) return;
        float multiplier = SprintFlyBoost.clientMultiplier();
        if (multiplier == SprintFlyBoost.VANILLA) return;
        cir.setReturnValue(cir.getReturnValueF() * multiplier);
    }
}
