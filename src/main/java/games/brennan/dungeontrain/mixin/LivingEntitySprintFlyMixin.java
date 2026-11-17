package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import games.brennan.dungeontrain.player.SprintFlyBoost;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Scales creative sprint-flying by {@link SprintFlyBoost} ({@code /dt flyspeed}).
 *
 * <p>Off the ground, {@code LivingEntity.getFrictionInfluencedSpeed} is {@code getFlyingSpeed()}: the
 * horizontal input {@code travel} feeds to {@code moveRelative}. Boosting here rather than on
 * {@code Player.getFlyingSpeed} keeps the boost clear of other mods' cancellable hooks there (Dungeon
 * Totems' morph air pace used to end that method before ours ran). Vertical flight is added separately in
 * {@code LocalPlayer.aiStep} straight from {@code abilities.flyingSpeed}, so up/down stays vanilla.
 * Gated to flying + sprinting, so plain flight is untouched too. Client-side only: that's where player
 * movement is simulated.</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySprintFlyMixin {

    @ModifyReturnValue(method = "getFrictionInfluencedSpeed", at = @At("RETURN"))
    private float dungeontrain$boostSprintFly(float speed) {
        if (!((Object) this instanceof Player self)) return speed;
        if (!self.level().isClientSide() || self.onGround()) return speed;
        if (!self.getAbilities().flying || self.isPassenger() || !self.isSprinting()) return speed;
        float multiplier = SprintFlyBoost.clientMultiplier();
        return multiplier == SprintFlyBoost.VANILLA ? speed : speed * multiplier;
    }
}
