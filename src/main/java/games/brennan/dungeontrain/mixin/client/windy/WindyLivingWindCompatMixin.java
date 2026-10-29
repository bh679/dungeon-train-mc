package games.brennan.dungeontrain.mixin.client.windy;

import games.brennan.dungeontrain.client.wind.WindyDrive;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Answers Windy: Configurable's "Living Wind" hook with DT's train wind, while DT owns the wind
 * ({@link WindyDrive#owns}):
 * <ul>
 *   <li>{@code isInstalled}/{@code isActive} — yes, so Windy uses the hook: spawns by our density, and turns
 *       each streak's sprite to face the wind (it only rotates sprites when the hook reports installed);</li>
 *   <li>{@code strength} — DT's push on Windy's scale (picks the strong streak in gusts);</li>
 *   <li>{@code directionDegrees} / {@code velocityX/Z} — DT's heading, at a speed that rises with the wind
 *       (Windy's own is a fixed 0.055/0.1 blocks/tick);</li>
 *   <li>{@code spawnRateMultiplier} — DT's density, which rises with the wind past Windy's 4× cap.</li>
 * </ul>
 * Calm before the Upside-down end, rising after it; held calm whenever DT's wind is off in a train world. {@code @Pseudo} + string target, so DT neither compiles
 * against Windy nor needs it installed; gated by {@code WindyMixinPlugin}, which checks the methods exist.
 */
@Pseudo
@Mixin(targets = "xyz.bonfiremc.windy.LivingWindCompat", remap = false)
public abstract class WindyLivingWindCompatMixin {

    @Inject(method = "isInstalled()Z", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$isInstalled(CallbackInfoReturnable<Boolean> cir) {
        if (WindyDrive.owns()) cir.setReturnValue(Boolean.TRUE);
    }

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

    @Inject(method = "velocityX(Z)D", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$velocityX(boolean strong, CallbackInfoReturnable<Double> cir) {
        if (WindyDrive.owns()) cir.setReturnValue(WindyDrive.velocityX(strong));
    }

    @Inject(method = "velocityZ(Z)D", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$velocityZ(boolean strong, CallbackInfoReturnable<Double> cir) {
        if (WindyDrive.owns()) cir.setReturnValue(WindyDrive.velocityZ(strong));
    }

    @Inject(method = "spawnRateMultiplier(F)D", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dungeontrain$spawnRate(float strength, CallbackInfoReturnable<Double> cir) {
        if (WindyDrive.owns()) cir.setReturnValue(WindyDrive.spawnRate());
    }
}
