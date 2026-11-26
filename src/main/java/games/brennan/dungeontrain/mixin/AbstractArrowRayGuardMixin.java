package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.ship.sable.ArrowRayGuardHooks;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops one arrow's block raycast from walking millions of blocks across the carriage plot and
 * world spaces. That walk froze a player's client for 1 to 6 seconds.
 *
 * <p>{@code tick} HEAD: if this tick's ray would be absurd (see
 * {@link games.brennan.dungeontrain.ship.sable.ArrowRayGuard}), zero the delta before vanilla reads
 * it. {@code onHitBlock} HEAD: an arrow already in plot space is moved back to world space, which
 * is what Sable's {@code onHitBlock} redirects expect. These are {@code @Inject}s, not
 * {@code @Redirect}s, because Sable redirects inside {@code onHitBlock} itself. A normal
 * world-space arrow passes through both unchanged.</p>
 */
@Mixin(AbstractArrow.class)
public abstract class AbstractArrowRayGuardMixin {

    @Shadow
    protected boolean inGround;

    /** One log line per arrow per kind, so a re-homed arrow still logs a later absurd ray. */
    @Unique
    private boolean dungeontrain$tickGuardLogged;

    @Unique
    private boolean dungeontrain$rehomeLogged;

    @Inject(method = "tick", at = @At("HEAD"))
    private void dungeontrain$guardRay(CallbackInfo ci) {
        if (ArrowRayGuardHooks.guardTick((AbstractArrow) (Object) this, this.inGround, this.dungeontrain$tickGuardLogged)) {
            this.dungeontrain$tickGuardLogged = true;
        }
    }

    @Inject(method = "onHitBlock", at = @At("HEAD"))
    private void dungeontrain$rehomeBeforeHit(BlockHitResult result, CallbackInfo ci) {
        if (ArrowRayGuardHooks.rehomeBeforeHitBlock((AbstractArrow) (Object) this, this.inGround, this.dungeontrain$rehomeLogged)) {
            this.dungeontrain$rehomeLogged = true;
        }
    }
}
