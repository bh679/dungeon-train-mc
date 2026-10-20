package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
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
 * Slants the falling rain and snow sheets with the train wind ({@link AmbientWind}). Each sheet vertex is
 * camera-relative, so shearing it by its height ({@code x += slant * y}) tilts the whole curtain about eye level,
 * downwind above and upwind below — the rain you look at leans into the wind's direction. Snow leans further than
 * rain. With no wind the slant is zero and the sheets are drawn as vanilla draws them.
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
        double sx = AmbientWind.slantX(dungeontrain$drawingSnow);
        double sz = AmbientWind.slantZ(dungeontrain$drawingSnow);
        if (sx == 0.0 && sz == 0.0) return original.call(builder, x, y, z);
        return original.call(builder, (float) (x + sx * y), y, (float) (z + sz * y));
    }
}
