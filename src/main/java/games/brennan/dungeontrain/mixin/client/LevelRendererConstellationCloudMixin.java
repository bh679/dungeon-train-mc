package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import games.brennan.dungeontrain.client.constellation.ConstellationClouds;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;

/**
 * While the constellation patch is open, the clouds mark the pixels they cover in one stencil bit, so
 * the patch can treat clouds as sky and hide them behind itself — see {@link ConstellationClouds}.
 *
 * <p>{@code @WrapMethod} rather than HEAD/TAIL injects: {@code LevelRendererVoidSkyMixin} cancels
 * cloud rendering at HEAD in the void bands, and a cancelled method never reaches TAIL. The wrap
 * restores the stencil state however the method ends.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererConstellationCloudMixin {

    @WrapMethod(method = "renderClouds(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FDDD)V")
    private void dungeontrain$markConstellationClouds(PoseStack poseStack, Matrix4f frustumMatrix, Matrix4f projectionMatrix,
                                                      float partialTick, double camX, double camY, double camZ,
                                                      Operation<Void> original) {
        boolean marking = ConstellationClouds.beginMark();
        try {
            original.call(poseStack, frustumMatrix, projectionMatrix, partialTick, camX, camY, camZ);
        } finally {
            if (marking) ConstellationClouds.endMark();
        }
    }
}
