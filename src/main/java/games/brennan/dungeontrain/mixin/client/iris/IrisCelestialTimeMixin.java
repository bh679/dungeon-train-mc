package games.brennan.dungeontrain.mixin.client.iris;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.shader.ShaderWorld;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Vector4f;
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
        if (angle != null) {
            cir.setReturnValue(angle);
            return;
        }
        // Upside-down: permanent dawn. Only reached when no Nether/End spoof is in force.
        Float dawn = ShaderWorld.upsideDownSkyAngle(cir.getReturnValueF());
        if (dawn != null) cir.setReturnValue(dawn);
    }

    /**
     * The direction the shadow map is rendered from, in world space — the one uniform BSL was found
     * to actually read. In the upside-down band the sun orbits the horizon, so the light it casts
     * is swung round with it, blended in by the band so it eases rather than snaps.
     */
    @Inject(method = "getShadowLightPositionInWorldSpace", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$horizonSunWorld(CallbackInfoReturnable<Vector4f> cir) {
        float[] d = ShaderWorld.upsideDownSunDirection();
        if (d == null) return;
        Vector4f real = cir.getReturnValue();
        cir.setReturnValue(dungeontrain$blendDir(real, d));
    }

    /** View-space twin, for packs that read {@code shadowLightPosition} instead. */
    @Inject(method = "getShadowLightPosition", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$horizonSunView(CallbackInfoReturnable<Vector4f> cir) {
        float[] d = ShaderWorld.upsideDownSunDirection();
        if (d == null) return;
        Vector4f world = new Vector4f(d[0], d[1], d[2], 0.0f);
        Vector4f view = new Vector4f(world).mul(RenderSystem.getModelViewMatrix());
        cir.setReturnValue(dungeontrain$blendDir(cir.getReturnValue(), new float[] { view.x, view.y, view.z, d[3] }));
    }

    private static Vector4f dungeontrain$blendDir(Vector4f real, float[] target) {
        float t = target[3];
        Vector4f out = new Vector4f(
            real.x + (target[0] - real.x) * t,
            real.y + (target[1] - real.y) * t,
            real.z + (target[2] - real.z) * t,
            real.w);
        float len = (float) Math.sqrt(out.x * out.x + out.y * out.y + out.z * out.z);
        if (len > 1.0e-5f) { out.x /= len; out.y /= len; out.z /= len; }
        // Iris hands these out at the scale it computed them in; keep that scale.
        float realLen = (float) Math.sqrt(real.x * real.x + real.y * real.y + real.z * real.z);
        if (realLen > 1.0e-5f) { out.x *= realLen; out.y *= realLen; out.z *= realLen; }
        return out;
    }
}
