package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.client.builder.ClientTrackGhost;
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
 * Leaves the Train Builder's corridor track out of the chunk mesh while a track template is open, so
 * {@code BuilderTrackGhostRenderer} can redraw it see-through.
 *
 * <p>Half of one effect. A block hidden here and not redrawn there is a hole in the world, so the
 * two share a single predicate — {@link ClientTrackGhost#isGhosted} — rather than each deciding for
 * itself.</p>
 *
 * <p>Hiding rather than tinting is what makes it a ghost. Real transparency needs the block in the
 * translucent chunk layer, and that layer is chosen per <em>block type</em>, not per position, so a
 * corridor whose every block is also the block the plot is made of cannot be split that way. Taking
 * it out of the mesh entirely and drawing it ourselves sidesteps the question, and gets the real
 * model with it — a rail ghosts as a rail rather than as a floating box, which a quad overlay like
 * {@code OutOfBoundsWashRenderer} can never manage.</p>
 *
 * <p>Targets the same <b>NeoForge 12-arg overload</b> of {@link ModelBlockRenderer#tesselateBlock}
 * that {@link ModelBlockRendererUpsideDownMixin} wraps, for the same reason: it is the one
 * NeoForge's {@code SectionCompiler} actually calls, and Sable runs vanilla meshing through it.
 * MixinExtras wrappers compose, so the two chain in either order — an upside-down band and an open
 * track plot are independent questions about the same block.</p>
 *
 * <p><b>Sodium replaces chunk meshing outright.</b> If this never fires, the track stays solid and
 * the ghost pass draws over it — the line reads bright rather than see-through. Cosmetic, and the
 * same exposure {@link ModelBlockRendererUpsideDownMixin} already ships with.</p>
 */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererTrackGhostMixin {

    @WrapMethod(method = "tesselateBlock(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;JILnet/neoforged/neoforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V")
    private void dungeontrain$hideGhostedTrack(BlockAndTintGetter level, BakedModel model, BlockState state,
                                               BlockPos pos, PoseStack poseStack, VertexConsumer consumer,
                                               boolean checkSides, RandomSource random, long seed,
                                               int packedOverlay, ModelData modelData, RenderType renderType,
                                               Operation<Void> original) {
        // One volatile read on the overwhelmingly common path — no builder world, or no track
        // template open — before anything else is touched. This runs for every block of every
        // chunk mesh in the game.
        if (ClientTrackGhost.isActive() && ClientTrackGhost.isGhosted(pos)) {
            return;
        }
        original.call(level, model, state, pos, poseStack, consumer, checkSides, random, seed,
                packedOverlay, modelData, renderType);
    }
}
