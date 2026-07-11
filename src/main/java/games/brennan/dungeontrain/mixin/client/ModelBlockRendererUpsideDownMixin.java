package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import games.brennan.dungeontrain.client.ClientUpsideDownBand;
import games.brennan.dungeontrain.client.UpsideDownBakedModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Renders every block whose world-X is inside an upside-down band visually flipped, by swapping its
 * model for a {@link UpsideDownBakedModel} (which serves vertically-mirrored quads). The flip is baked
 * into the compiled section geometry with the same face count as normal — <b>nothing per frame</b>.
 *
 * <p>Targets the <b>NeoForge 12-arg overload</b> of {@link ModelBlockRenderer#tesselateBlock} (the one
 * with trailing {@code ModelData, RenderType}) — that is the overload NeoForge's {@code SectionCompiler}
 * actually calls during chunk meshing (it bypasses both {@code BlockRenderDispatcher.renderBatched} and
 * the vanilla 10-arg {@code tesselateBlock}). Sable runs vanilla chunk meshing here (it only augments
 * shading via {@code putQuadData}/{@code renderModelFaceFlat}), so wrapping this method places the flip
 * on the live path. Flipping at the model level (not by rotating geometry) keeps face-culling correct —
 * a rotation would swing the one surviving air-adjacent face to the far side, leaving the block
 * inside-out.</p>
 *
 * <p>Gated on {@link ClientUpsideDownBand#isInBand} by the block's world-X, <b>with the ridable train
 * explicitly excluded</b>. The train is a Sable sub-level whose blocks are meshed at plot-space
 * coordinates (near the world border, ~X 20.4M) through this very {@code tesselateBlock} path — and
 * the band repeats forever via {@code Math.floorMod}, so those huge coordinates wrap back into the
 * cycle and can land <em>inside</em> the band. A world-X gate alone therefore flips the whole train.
 * {@link #dungeontrain$isSubLevelBlock} cancels the flip for any block inside the Sable plot grid, so
 * the train stays upright as the stable frame of reference while the world inverts around it. Purely
 * client-side and cosmetic; supersedes the old block-state {@code verticalFlip} in
 * {@code WorldUpsideDownEvents}.</p>
 */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererUpsideDownMixin {

    @WrapMethod(method = "tesselateBlock(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;JILnet/neoforged/neoforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V")
    private void dungeontrain$flipUpsideDownBand(BlockAndTintGetter level, BakedModel model, BlockState state,
                                                 BlockPos pos, PoseStack poseStack, VertexConsumer consumer,
                                                 boolean checkSides, RandomSource random, long seed,
                                                 int packedOverlay, ModelData modelData, RenderType renderType,
                                                 Operation<Void> original) {
        // Full band + entry lead-in flips every block; the exit crossfade flips with a Y-split (only the
        // reflected ceiling at/above the mirror plane) so the dispersing mirror islands stay upside-down
        // while the returning overworld below renders upright.
        boolean bandFlip = pos != null
                && (ClientUpsideDownBand.isInBand(pos.getX())
                    || (ClientUpsideDownBand.isInExitFlip(pos.getX()) && pos.getY() >= ClientUpsideDownBand.plane()));
        // ...but never the train: its carriages are Sable sub-levels meshed at plot-space coordinates
        // that wrap into the band via the cycle's modulo, so the world-X gate above can't tell them
        // apart. Cancel the flip for any block in the plot grid so the train stays upright. Evaluated
        // only when a flip would otherwise happen (rare, meshed-once) — the no-flip hot path is untouched.
        boolean flip = bandFlip && !dungeontrain$isSubLevelBlock(pos);
        if (!flip) {
            original.call(level, model, state, pos, poseStack, consumer, checkSides, random, seed, packedOverlay, modelData, renderType);
            return;
        }
        // Flip at the MODEL level (vertically-mirrored quads), not by rotating geometry — culling has
        // already dropped hidden faces, so rotating would leave the block inside-out.
        BakedModel flipped = UpsideDownBakedModel.of(model);
        original.call(level, flipped, state, pos, poseStack, consumer, checkSides, random, seed, packedOverlay, modelData, renderType);
    }

    /**
     * True if {@code pos} lies inside the Sable sub-level plot grid — i.e. it is a train/carriage block,
     * not real overworld terrain. {@link ClientSubLevelContainer#inBounds(BlockPos)} is a cheap bit-shift
     * range test (no allocation, no per-plot lookup); the plot grid is a dedicated high-coordinate region
     * overworld terrain never occupies, so a positive result reliably means "sub-level block". Reuses the
     * established client idiom ({@code SubLevelContainer.getContainer(ClientLevel)}) shared with
     * {@code client.snapshot.NearestCarriage} / {@code CarriageOcclusion}. Returns {@code false} (→ flip
     * as before) when there is no level or container, so the worst case is pre-fix behaviour.
     */
    @Unique
    private static boolean dungeontrain$isSubLevelBlock(BlockPos pos) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return false;
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        return container != null && container.inBounds(pos);
    }
}
