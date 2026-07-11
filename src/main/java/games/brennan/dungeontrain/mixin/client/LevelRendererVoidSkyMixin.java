package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.client.ClientNetherBand;
import games.brennan.dungeontrain.client.ClientUpsideDownBand;
import games.brennan.dungeontrain.client.ClientVoidBand;
import games.brennan.dungeontrain.client.NetherSkyRenderer;
import games.brennan.dungeontrain.client.UpsideDownSkyRenderer;
import games.brennan.dungeontrain.client.VoidSkyRenderer;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Train-band atmosphere hooks on {@code LevelRenderer}:
 * <ul>
 *   <li>{@code renderSky} TAIL → overlay the band skybox at its opacity, after vanilla has
 *       drawn the overworld sky (clean crossfade, no pop): the End starfield
 *       ({@link VoidSkyRenderer}) and the Nether fog-colour fill ({@link NetherSkyRenderer}).
 *       The two bands never overlap in world-X, so at most one paints.</li>
 *   <li>{@code renderClouds} HEAD → cancel cloud rendering once the End or Nether sky has
 *       mostly faded in, so clouds disappear over the void/End rather than floating
 *       incongruously above it.</li>
 *   <li>{@code renderClouds} getCloudHeight → in the <b>upside-down</b> band the sky sits below the
 *       train, so rather than hide the clouds, lerp the cloud plane down toward the configured
 *       {@code upsideDownCloudY} by the band intensity — the clouds sink beneath you as the flip fades in.</li>
 *   <li>{@code renderSnowAndRain} HEAD → cancel falling rain/snow over the Nether
 *       core, so storms don't rain on the hellscape (the Nether has no weather).</li>
 * </ul>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererVoidSkyMixin {

    /** Above this End-sky intensity, clouds are hidden. */
    private static final double DUNGEONTRAIN_CLOUD_HIDE_THRESHOLD = 0.5;

    @Inject(
            method = "renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V",
            at = @At("TAIL")
    )
    private void dungeontrain$bandSkyOverlay(Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick,
                                             Camera camera, boolean isFoggy, Runnable skyFogSetup, CallbackInfo ci) {
        VoidSkyRenderer.renderOverlay(frustumMatrix, camera, isFoggy);
        NetherSkyRenderer.renderOverlay(frustumMatrix, camera, isFoggy);
        UpsideDownSkyRenderer.renderOverlay(frustumMatrix, camera, partialTick, isFoggy);
    }

    @Inject(
            method = "renderClouds(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FDDD)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void dungeontrain$hideCloudsInVoid(com.mojang.blaze3d.vertex.PoseStack poseStack, Matrix4f frustumMatrix,
                                               Matrix4f projectionMatrix, float partialTick,
                                               double camX, double camY, double camZ, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return;
        if (ClientVoidBand.endSkyIntensityAt(camX) > DUNGEONTRAIN_CLOUD_HIDE_THRESHOLD
                || ClientNetherBand.netherIntensityAt(camX) > DUNGEONTRAIN_CLOUD_HIDE_THRESHOLD) {
            ci.cancel();
        }
    }

    /**
     * Lower the cloud plane in the upside-down band. The flipped world's sky sits <em>below</em> the
     * train, so instead of hiding clouds (as the void/Nether do above), redirect the
     * {@code getCloudHeight()} that {@code renderClouds} reads toward the configured
     * {@code upsideDownCloudY}, lerped by the band intensity so the clouds sink beneath you as the flip
     * fades in and rise back out on exit. Outside the band the original height (192) is kept unchanged.
     */
    @ModifyExpressionValue(
            method = "renderClouds(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FDDD)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/DimensionSpecialEffects;getCloudHeight()F"
            )
    )
    private float dungeontrain$lowerCloudsInUpsideDown(float original, @Local(argsOnly = true, ordinal = 0) double camX) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return original;
        double t = ClientUpsideDownBand.upsideDownIntensityAt(camX);
        if (t <= 0.0) return original;
        return Mth.lerp((float) t, original, DungeonTrainCommonConfig.getUpsideDownCloudY());
    }

    @Inject(
            method = "renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void dungeontrain$hideWeatherInNether(net.minecraft.client.renderer.LightTexture lightTexture,
                                                  float partialTick, double camX, double camY, double camZ,
                                                  CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return;
        if (ClientNetherBand.netherIntensityAt(camX) > DUNGEONTRAIN_CLOUD_HIDE_THRESHOLD) {
            ci.cancel();
        }
    }
}
