package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.client.shader.ShaderWorldCrossfade;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hands the one {@code LevelRenderer.renderLevel} call in {@code GameRenderer.renderLevel} to
 * {@link ShaderWorldCrossfade}, which calls it once — or twice, under a shader pack mid-way
 * between two of the pack's worlds, blending the two images. See that class.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererCrossfadeMixin {

    @WrapOperation(
        method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"
        )
    )
    private void dungeontrain$crossfadeShaderWorlds(LevelRenderer levelRenderer, DeltaTracker deltaTracker,
                                                    boolean renderBlockOutline, Camera camera,
                                                    GameRenderer gameRenderer, LightTexture lightTexture,
                                                    Matrix4f frustumMatrix, Matrix4f projectionMatrix,
                                                    Operation<Void> original) {
        ShaderWorldCrossfade.render(levelRenderer, deltaTracker, renderBlockOutline, camera, gameRenderer,
            lightTexture, frustumMatrix, projectionMatrix, original);
    }
}
