package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.ClientNetherBand;
import games.brennan.dungeontrain.client.ClientVoidBand;
import games.brennan.dungeontrain.client.NetherSkyRenderer;
import games.brennan.dungeontrain.client.VoidSkyRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
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
 *       mostly faded in, so clouds disappear over the void/End/Nether rather than floating
 *       incongruously above it.</li>
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
}
