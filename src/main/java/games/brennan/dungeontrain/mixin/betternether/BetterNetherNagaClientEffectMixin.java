package games.brennan.dungeontrain.mixin.betternether;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.betterx.betternether.entity.EntityNagaProjectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps a Naga's Wither on the server. BetterNether's {@code EntityNagaProjectile} looks for hits in
 * {@code tick} on both sides, and on an entity hit {@code onCollision} gives the target Wither II for
 * 10 seconds with no client check. When the client sees a hit the server misses (a player on a moving
 * train sees the shot cross them a little differently), the player's own client gives itself Wither II
 * that the server never had: it does no damage, counts down to 00:00 and stays there, since only the
 * server's remove packet clears a client effect, and milk can't reach it.
 *
 * <p>The {@code addEffect} call is skipped on the client; the server's hit still applies it and syncs
 * it as usual. The projectile's particles and despawn are untouched. Guarded by
 * {@code ThirdPartyMixinPlugin} ({@code compat/mixinguard/ThirdPartyMixinTargets}).</p>
 */
@Mixin(value = EntityNagaProjectile.class, remap = false)
public abstract class BetterNetherNagaClientEffectMixin {

    @WrapWithCondition(method = "onCollision", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/LivingEntity;addEffect(Lnet/minecraft/world/effect/MobEffectInstance;)Z"))
    private boolean dungeontrain$witherOnServerOnly(LivingEntity target, MobEffectInstance effect) {
        return !((Entity) (Object) this).level().isClientSide();
    }
}
