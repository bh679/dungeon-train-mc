package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * How the Train Builder draws a ghost: from each cell's own baked block model.
 *
 * <p>The one tool the builder's three ghost renderers share — the line around an open track tile
 * ({@link BuilderTrackGhostRenderer}), a portal room repeating at its walls
 * ({@link BuilderRoomGhostRenderer}), and the rest of the train around a parked carriage
 * ({@link BuilderGhostTrainRenderer}). All three answer the same question — "what would be here,
 * that isn't?" — and a flat silhouette answers it badly: what a builder is judging is a real join
 * against real blocks, and an abstract shape hanging in the air tells them nothing about it.</p>
 *
 * <p>Two rules keep a mass of ghosts legible, and they are why this is one place rather than
 * three:</p>
 * <ul>
 *   <li>a face is drawn only when the neighbouring cell is <em>absent</em>, so a run contributes
 *       its outer shell rather than every internal face stacked through it. The unculled quads
 *       always go in regardless — that is where a non-cube keeps its geometry, so a rail or a torch
 *       drawn without them would be nothing at all;</li>
 *   <li>the batch is culled and depth-writing ({@link RenderType#entityTranslucentCull}), so a
 *       caller that feeds it nearest-first gets the closest surface winning each pixel and nothing
 *       ghosted rendering through anything else ghosted. The opaque world is drawn before this
 *       pass, so solid geometry behind a ghost still shows through the blend.</li>
 * </ul>
 *
 * <p><b>Ordering is the caller's.</b> This draws what it is handed in the order it is handed it —
 * the front-to-back sort is per-renderer, because what there is to sort differs: the track ghosts
 * sort cells, and the room and train ghosts sort whole tiles and slots, which is a hundred-odd
 * comparisons instead of a hundred thousand.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderGhostCells {

    /** Textured, translucent, culled and depth-writing — see the class note. */
    public static final RenderType GHOST = RenderType.entityTranslucentCull(InventoryMenu.BLOCK_ATLAS);

    /** Pale blue over the block's own texture, so a ghost still reads as a ghost. */
    public static final float TINT_R = 0.80F;
    public static final float TINT_G = 0.86F;
    public static final float TINT_B = 1.00F;

    private BuilderGhostCells() {}

    /**
     * Draw a batch of ghost cells, offset into the world.
     *
     * @param draw       the cells to draw, keyed by position in the caller's local frame
     * @param neighbours the map the face-culling test consults. Usually {@code draw} itself; it is
     *                   separate so a caller drawing part of a shape (a distant room's floor course
     *                   alone) can still cull against the whole of it, and so a caller assembled
     *                   from two sources can cull against their union
     * @param lookup     maps a local neighbour position to the key to test in {@code neighbours} —
     *                   {@link UnaryOperator#identity()} for a plain shape, and where a tiled copy
     *                   wraps its lookup around the grid so the seam between two copies is culled
     *                   as the interior it is
     * @param offset     added to each local position to place it in the world. The pose stack is
     *                   expected to already be translated by {@code -camera}
     */
    public static void draw(PoseStack ps, VertexConsumer vc, BlockRenderDispatcher blocks,
                            Level level, RandomSource random,
                            Map<BlockPos, BlockState> draw, Map<BlockPos, BlockState> neighbours,
                            UnaryOperator<BlockPos> lookup, BlockPos offset, float alpha) {
        for (Map.Entry<BlockPos, BlockState> entry : draw.entrySet()) {
            BlockPos local = entry.getKey();
            int wx = offset.getX() + local.getX();
            int wy = offset.getY() + local.getY();
            int wz = offset.getZ() + local.getZ();

            ps.pushPose();
            ps.translate(wx, wy, wz);
            drawCell(ps, vc, blocks, entry.getValue(), local, neighbours, lookup,
                    LevelRenderer.getLightColor(level, new BlockPos(wx, wy, wz)), random, alpha);
            ps.popPose();
        }
    }

    /**
     * One ghost block, from its own model, with the pose already at its corner.
     *
     * <p>Public because the track ghosts drive their own per-cell loop: they sort cells rather than
     * batches, so they translate and light each one themselves and only want this part.</p>
     */
    public static void drawCell(PoseStack ps, VertexConsumer vc, BlockRenderDispatcher blocks,
                                BlockState state, BlockPos local,
                                Map<BlockPos, BlockState> neighbours, UnaryOperator<BlockPos> lookup,
                                int light, RandomSource random, float alpha) {
        BakedModel model = blocks.getBlockModel(state);
        long seed = state.getSeed(local);

        random.setSeed(seed);
        putQuads(ps, vc, model.getQuads(state, null, random, ModelData.EMPTY, null), light, alpha);

        for (Direction dir : Direction.values()) {
            if (neighbours.containsKey(lookup.apply(local.relative(dir)))) {
                continue;
            }
            random.setSeed(seed);
            putQuads(ps, vc, model.getQuads(state, dir, random, ModelData.EMPTY, null), light, alpha);
        }
    }

    private static void putQuads(PoseStack ps, VertexConsumer vc, List<BakedQuad> quads,
                                 int light, float alpha) {
        for (BakedQuad quad : quads) {
            vc.putBulkData(ps.last(), quad, TINT_R, TINT_G, TINT_B, alpha, light,
                    OverlayTexture.NO_OVERLAY);
        }
    }
}
