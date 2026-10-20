package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.client.wind.AmbientWind;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Blows the falling weather with the train wind ({@link AmbientWind}):
 * <ul>
 *   <li><b>Rain slants.</b> Each sheet vertex is camera-relative, so shearing it by its height
 *       ({@code x -= slant * y}) tilts the whole curtain about eye level the way a blown drop travels: upwind
 *       above, downwind below. The slant follows the visual wind the streaks show, which is the wind relative to
 *       the carriage, so from a moving train the rain leans exactly as the push is felt.</li>
 *   <li><b>Snow scrolls.</b> Its sheets stay upright and the flakes are carried sideways: the snow texture's U is
 *       offset along each column quad's own horizontal axis (vanilla's {@code rainSizeX/Z} half-axis, locals
 *       {@code d0}/{@code d1}) by how far the snow has blown.</li>
 * </ul>
 * With no wind both are zero and the sheets are drawn as vanilla draws them.
 *
 * <p>Vertex-level, not a matrix push, so it can't unbalance anything when another injector cancels the method
 * (the Nether/End bands do), and Iris's weather pass draws the same sheared geometry.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererWeatherSlantMixin {

    @Shadow @Final private static ResourceLocation SNOW_LOCATION;

    /** Which sheet the current batch is: set as vanilla binds the rain or snow texture. */
    @Unique private boolean dungeontrain$drawingSnow = false;

    @WrapOperation(method = "renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V"))
    private void dungeontrain$noteSheet(int unit, ResourceLocation texture, Operation<Void> original) {
        dungeontrain$drawingSnow = SNOW_LOCATION.equals(texture);
        original.call(unit, texture);
    }

    @WrapOperation(method = "renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;addVertex(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private VertexConsumer dungeontrain$slantSheet(BufferBuilder builder, float x, float y, float z,
                                                   Operation<VertexConsumer> original) {
        if (dungeontrain$drawingSnow) return original.call(builder, x, y, z);
        double sx = AmbientWind.rainSlantX();
        double sz = AmbientWind.rainSlantZ();
        if (sx == 0.0 && sz == 0.0) return original.call(builder, x, y, z);
        // A drop blown downwind starts upwind and lands downwind: the top of the sheet sits upwind (-slant * y).
        return original.call(builder, (float) (x - sx * y), y, (float) (z - sz * y));
    }

    @WrapOperation(method = "renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;setUv(FF)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private VertexConsumer dungeontrain$scrollSnow(VertexConsumer consumer, float u, float v,
                                                   Operation<VertexConsumer> original,
                                                   @Local(argsOnly = true) float partialTick,
                                                   @Local(ordinal = 3) double halfAxisX,
                                                   @Local(ordinal = 4) double halfAxisZ) {
        if (!dungeontrain$drawingSnow || !AmbientWind.snowScrolling()) return original.call(consumer, u, v);
        // d0/d1 (doubles 3 and 4, after camX/Y/Z) are half the quad's unit axis; U runs 0 -> 1 from -axis to
        // +axis. If another mod has reshaped the method and they aren't, leave the snow as vanilla draws it.
        double axisX = halfAxisX * 2.0;
        double axisZ = halfAxisZ * 2.0;
        if (Math.abs(axisX * axisX + axisZ * axisZ - 1.0) > 0.01) return original.call(consumer, u, v);
        float du = AmbientWind.snowScrollU(axisX, axisZ, partialTick);
        return original.call(consumer, u + du, v);
    }
}
