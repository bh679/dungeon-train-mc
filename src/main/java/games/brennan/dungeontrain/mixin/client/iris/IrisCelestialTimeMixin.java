package games.brennan.dungeontrain.mixin.client.iris;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.shader.ShaderWorld;
import org.slf4j.Logger;
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

    private static final Logger DUNGEONTRAIN_LOGGER = LogUtils.getLogger();
    /**
     * Say once that this hook is live. The Iris mixin config runs at {@code defaultRequire: 0} so a
     * target that fails to match is silent — "no errors in the log" proves nothing about whether
     * any of this applied, which is exactly how an earlier fix here looked applied and was not.
     */
    private static boolean dungeontrain$announced = false;

    @Inject(method = "getSkyAngle", at = @At("RETURN"), cancellable = true)
    private static void dungeontrain$fixedTimeInSpoofedBand(CallbackInfoReturnable<Float> cir) {
        if (!dungeontrain$announced) {
            dungeontrain$announced = true;
            DUNGEONTRAIN_LOGGER.info("[DungeonTrain] Iris celestial hook is live (sky angle {}).",
                cir.getReturnValueF());
        }
        Float angle = ShaderWorld.spoofedSkyAngle();
        if (angle != null) cir.setReturnValue(angle);
    }
}
