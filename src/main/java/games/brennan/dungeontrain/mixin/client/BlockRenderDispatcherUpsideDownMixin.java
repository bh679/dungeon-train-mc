package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import games.brennan.dungeontrain.client.ClientUpsideDownBand;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Renders every block whose world-X is inside an upside-down band visually flipped, by wrapping the
 * vanilla batched block render with a 180° rotation about the block centre. Chunk meshing is vanilla
 * here (Sable logs "Using Vanilla renderer mixins"; no Sodium/Embeddium), so the rotation is baked
 * into the compiled section geometry — it costs only a few matrix ops per band block <b>at mesh time</b>
 * (sections already re-mesh on load/change) and <b>nothing per frame</b>. This is the minimal-cost way
 * to make the mirror band read as upside-down without a shader or a per-frame transform.
 *
 * <p>A 180° <em>rotation</em> (a proper, determinant-+1 transform) turns each model upside down without
 * the backface-culling breakage a {@code scale(1,-1,1)} mirror would cause. It supersedes the old
 * block-state {@code verticalFlip} in {@code WorldUpsideDownEvents} (which now writes source states
 * unchanged) — the mirror handler only moves block positions; this handler flips their appearance.</p>
 *
 * <p>Gated purely on {@link ClientUpsideDownBand#isInBand} by the block's world-X. The ridable train is
 * a Sable sub-level whose blocks live in sub-level coordinates (not the band's world-X), so it is
 * naturally left upright; if in-game testing ever shows a flipped carriage, add a sub-level guard here.
 * Purely client-side and cosmetic.</p>
 */
@Mixin(BlockRenderDispatcher.class)
public abstract class BlockRenderDispatcherUpsideDownMixin {

    @WrapMethod(method = "renderBatched")
    private void dungeontrain$flipUpsideDownBand(BlockState state, BlockPos pos, BlockAndTintGetter level,
                                                 PoseStack poseStack, VertexConsumer consumer, boolean checkSides,
                                                 RandomSource random, Operation<Void> original) {
        if (!ClientUpsideDownBand.isInBand(pos.getX())) {
            original.call(state, pos, level, poseStack, consumer, checkSides, random);
            return;
        }
        poseStack.pushPose();
        poseStack.translate(0.5, 0.5, 0.5);
        poseStack.mulPose(Axis.XP.rotationDegrees(180.0F));   // flip upside down about the block centre
        poseStack.translate(-0.5, -0.5, -0.5);
        original.call(state, pos, level, poseStack, consumer, checkSides, random);
        poseStack.popPose();
    }
}
