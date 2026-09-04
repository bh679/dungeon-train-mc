package games.brennan.dungeontrain.mixin.client.iris;

import games.brennan.dungeontrain.client.shader.ShaderWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pins the {@code worldTime} uniform to dawn inside the upside-down band.
 *
 * <p>Pinning the sky angle was not enough: packs read {@code worldTime} directly for their
 * brightness and day/night factors, so with the sun drawn at sunrise the world was still lit for
 * midnight. Same anchor tick as the angle, eased the short way round the day, so the two agree.</p>
 */
@Mixin(targets = "net.irisshaders.iris.uniforms.WorldTimeUniforms", remap = false)
public abstract class IrisWorldTimeMixin {

    @Inject(method = "getWorldDayTime", at = @At("RETURN"), cancellable = true)
    private static void dungeontrain$dawnInUpsideDown(CallbackInfoReturnable<Integer> cir) {
        Integer t = ShaderWorld.upsideDownWorldTime(cir.getReturnValueI());
        if (t != null) cir.setReturnValue(t);
    }
}
