package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.client.UpsideDownRenderFlip;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Renders every block the upside-down band covers visually flipped, by swapping its model for a
 * {@link games.brennan.dungeontrain.client.UpsideDownBakedModel} — see
 * {@link UpsideDownRenderFlip} for which blocks flip and why.
 *
 * <p>Targets the <b>NeoForge 12-arg overload</b> of {@link ModelBlockRenderer#tesselateBlock} (the one
 * with trailing {@code ModelData, RenderType}) — that is the overload NeoForge's {@code SectionCompiler}
 * actually calls during chunk meshing (it bypasses both {@code BlockRenderDispatcher.renderBatched} and
 * the vanilla 10-arg {@code tesselateBlock}).</p>
 *
 * <p><b>This is the vanilla-pipeline half of the flip only.</b> Sodium replaces chunk meshing wholesale
 * and never calls {@code tesselateBlock}, so the same swap is applied on Sodium's own entry point by
 * {@code mixin.client.sodium.BlockRendererUpsideDownMixin}. Both delegate to the same predicate; which
 * one is live depends purely on whether Sodium (and therefore Iris) is installed.</p>
 */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererUpsideDownMixin {

    @WrapMethod(method = "tesselateBlock(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;JILnet/neoforged/neoforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V")
    private void dungeontrain$flipUpsideDownBand(BlockAndTintGetter level, BakedModel model, BlockState state,
                                                 BlockPos pos, PoseStack poseStack, VertexConsumer consumer,
                                                 boolean checkSides, RandomSource random, long seed,
                                                 int packedOverlay, ModelData modelData, RenderType renderType,
                                                 Operation<Void> original) {
        original.call(level, UpsideDownRenderFlip.apply(model, pos), state, pos, poseStack, consumer,
                checkSides, random, seed, packedOverlay, modelData, renderType);
    }
}
