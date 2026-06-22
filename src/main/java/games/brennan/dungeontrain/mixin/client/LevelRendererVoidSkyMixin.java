package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.VoidSkyRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Overlays the End skybox onto the overworld sky at the void-band opacity, after
 * vanilla has finished drawing the normal sky. TAIL runs at the natural end of
 * {@code renderSky}; for the overworld, {@code effects().renderSky(...)} returns
 * false (no early return), so vanilla draws the full overworld sky and then this
 * fires with the same {@code frustumMatrix}/{@code camera} — giving a clean
 * crossfade rather than a hard sky swap. See {@link VoidSkyRenderer}.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererVoidSkyMixin {

    @Inject(
            method = "renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V",
            at = @At("TAIL")
    )
    private void dungeontrain$voidSkyOverlay(Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick,
                                             Camera camera, boolean isFoggy, Runnable skyFogSetup, CallbackInfo ci) {
        VoidSkyRenderer.renderOverlay(frustumMatrix, camera, isFoggy);
    }
}
