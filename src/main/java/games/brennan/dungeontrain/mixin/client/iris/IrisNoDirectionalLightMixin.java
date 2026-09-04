package games.brennan.dungeontrain.mixin.client.iris;

import games.brennan.dungeontrain.client.shader.ShaderWorld;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Takes the directional light out of a spoofed Nether or End band.
 *
 * <h2>What this fixes</h2>
 * <p>The band is the overworld, so it has a sun, and a pack's Nether programs happily light the
 * world with it: enclosed tunnels read as though sunlight is coming through the walls. Switching
 * the world spoof off cures it and switching Dungeon Train's own lightmap lift off does not, which
 * places the cause squarely on the pack's direct lighting rather than anything in the lightmap.</p>
 *
 * <p>The real Nether has no directional light at all — it is lit by block light and a flat ambient
 * term — so the honest imitation is to have none either. Every direct-light uniform a pack reads
 * ({@code shadowLightPosition}, {@code sunPosition}, {@code moonPosition}) is pointed straight down
 * through the floor. A surface can then never face the light, {@code NdotL} is negative everywhere,
 * and the pack's own maths puts the whole world in shadow without it having to be asked.</p>
 *
 * <p>Pointing the light rather than trying to zero it is deliberate: there is no "no light" uniform
 * to set, and a pack that divides by the light vector would break on a zero one. A direction that
 * simply never faces anything is the safe way to say the same thing.</p>
 */
@Mixin(targets = "net.irisshaders.iris.uniforms.CelestialUniforms", remap = false)
public abstract class IrisNoDirectionalLightMixin {

    /** Straight down, in the space these uniforms are expressed in. Nothing faces it. */
    private static Vector4f dungeontrain$below() {
        return new Vector4f(0.0f, -1.0f, 0.0f, 0.0f);
    }

    private static boolean dungeontrain$suppress() {
        ShaderWorld.World w = ShaderWorld.reporting();
        return w != null && w != ShaderWorld.World.OVERWORLD;
    }

    @Inject(method = "getShadowLightPosition", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$noShadowLight(CallbackInfoReturnable<Vector4f> cir) {
        if (dungeontrain$suppress()) cir.setReturnValue(dungeontrain$below());
    }

    @Inject(method = "getSunPosition", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$noSun(CallbackInfoReturnable<Vector4f> cir) {
        if (dungeontrain$suppress()) cir.setReturnValue(dungeontrain$below());
    }

    @Inject(method = "getMoonPosition", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$noMoon(CallbackInfoReturnable<Vector4f> cir) {
        if (dungeontrain$suppress()) cir.setReturnValue(dungeontrain$below());
    }
}
