package games.brennan.dungeontrain.mixin.client.iris;

import games.brennan.dungeontrain.client.shader.ShaderWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives a spoofed band the sun the dimension it is imitating would actually have.
 *
 * <p>Telling Iris "this is the Nether" swaps the pack's programs but not the sky's clock, so the
 * band kept the overworld's travelling sun: the train was lit and shadowed from a direction the
 * real Nether never has. The Nether and the End are fixed-time dimensions, and every celestial
 * uniform a pack reads — {@code sunAngle}, {@code sunPosition}, {@code moonPosition},
 * {@code shadowLightPosition} — is derived from this one method.</p>
 *
 * <p>Hooking here rather than {@code Level.getTimeOfDay} is deliberate. That method also drives
 * vanilla's own sky darkening and world lighting, and forcing it to the Nether's midnight would
 * plunge a band that still has a real sky into darkness. This reaches Iris' uniforms and nothing
 * else.</p>
 */
@Mixin(targets = "net.irisshaders.iris.uniforms.CelestialUniforms", remap = false)
public abstract class IrisCelestialTimeMixin {

    @Inject(method = "getSkyAngle", at = @At("RETURN"), cancellable = true)
    private static void dungeontrain$fixedTimeInSpoofedBand(CallbackInfoReturnable<Float> cir) {
        Float angle = ShaderWorld.spoofedSkyAngle();
        if (angle != null) cir.setReturnValue(angle);
    }
}
