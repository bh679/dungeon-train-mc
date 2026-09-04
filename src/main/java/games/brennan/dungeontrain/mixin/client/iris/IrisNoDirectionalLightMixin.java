package games.brennan.dungeontrain.mixin.client.iris;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.shader.ShaderWorld;
import org.slf4j.Logger;
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

    private static final Logger DUNGEONTRAIN_LOGGER = LogUtils.getLogger();
    /**
     * One line per hook, the first time each fires. Without it an override that silently stopped
     * matching is indistinguishable from one that matched and did not help — the config runs at
     * {@code defaultRequire: 0}, so nothing complains either way.
     */
    private static final java.util.Set<String> DUNGEONTRAIN_ANNOUNCED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void dungeontrain$announce(String hook) {
        if (DUNGEONTRAIN_ANNOUNCED.add(hook)) {
            DUNGEONTRAIN_LOGGER.info("[DungeonTrain] Iris direct-light hook live: {}", hook);
        }
    }

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
        dungeontrain$announce("shadowLightPosition");
        if (dungeontrain$suppress()) cir.setReturnValue(dungeontrain$below());
    }

    /**
     * The one the shadow <em>render</em> uses. {@code ShadowRenderer} builds its model-view from
     * this, so leaving it alone meant the shadow map was still being rendered from the overworld
     * sun's direction however the view-space uniform was answered — which is why pointing the other
     * three downward changed nothing a player could see.
     */
    @Inject(method = "getShadowLightPositionInWorldSpace", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$noWorldShadowLight(CallbackInfoReturnable<Vector4f> cir) {
        dungeontrain$announce("shadowLightPositionInWorldSpace");
        if (dungeontrain$suppress()) cir.setReturnValue(dungeontrain$below());
    }

    @Inject(method = "getSunPosition", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$noSun(CallbackInfoReturnable<Vector4f> cir) {
        dungeontrain$announce("sunPosition");
        if (dungeontrain$suppress()) cir.setReturnValue(dungeontrain$below());
    }

    @Inject(method = "getMoonPosition", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$noMoon(CallbackInfoReturnable<Vector4f> cir) {
        dungeontrain$announce("moonPosition");
        if (dungeontrain$suppress()) cir.setReturnValue(dungeontrain$below());
    }
}
