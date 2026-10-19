package games.brennan.dungeontrain.mixin.client.windy;

import games.brennan.dungeontrain.client.wind.WindyDrive;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Answers Windy: Configurable's "Living Wind" hook with DT's train wind, so Windy's own streaks spawn at DT's
 * strength, turn to face DT's heading and drift with it. While DT owns the wind ({@link WindyDrive#owns}),
 * Windy's random ambient wind is replaced: calm before the Upside-down end, rising after it.
 *
 * <p>{@code @Pseudo} + string target, so DT neither compiles against Windy nor needs it installed; gated by
 * {@code WindyMixinPlugin}, which also checks the three methods still exist.</p>
 */
@Pseudo
@Mixin(targets = "xyz.bonfiremc.windy.LivingWindCompat", remap = false)
public abstract class WindyLivingWindCompatMixin {

    @Inject(method = "isActive()Z", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$isActive(CallbackInfoReturnable<Boolean> cir) {
        if (WindyDrive.owns()) cir.setReturnValue(Boolean.TRUE);
    }

    @Inject(method = "strength()F", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$strength(CallbackInfoReturnable<Float> cir) {
        if (WindyDrive.owns()) cir.setReturnValue(WindyDrive.strength());
    }

    @Inject(method = "directionDegrees()F", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$direction(CallbackInfoReturnable<Float> cir) {
        if (WindyDrive.owns()) cir.setReturnValue(WindyDrive.yawDegrees());
    }
}
