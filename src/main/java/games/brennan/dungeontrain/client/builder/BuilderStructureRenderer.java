package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.structure.BuilderStructureNeeds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Draws what {@link BuilderStructureNeeds} says is around the build, as textured ghosts.
 *
 * <p>One renderer for every mode, where there used to be three that did not know about each other —
 * one for the rest of the train, one for a portal room's tiling, one for the line around a track
 * template. Each answered the same question in its own shape, only two of them were textured, and
 * the control on top of them governed exactly one.</p>
 *
 * <table border="1">
 *   <caption>What the structure control means, uniformly in every mode</caption>
 *   <tr><th></th><th>Real blocks?</th><th>Drawn here?</th></tr>
 *   <tr><td>Ghost</td><td>no</td><td>translucent</td></tr>
 *   <tr><td>Solid</td><td>yes — stood up by {@code BuilderStructureStamp}</td><td>no need</td></tr>
 *   <tr><td>None</td><td>no</td><td>no</td></tr>
 * </table>
 *
 * <p><b>Textured, from each cell's own block model.</b> A structure ghosts as the thing it is — its
 * stone, its rails, its lamps — because what you are judging is a real space against the one you are
 * building, and flat silhouette quads read as an abstract shape hanging in the air. Two rules keep a
 * mass of them legible: a face is drawn only where the neighbouring cell is absent, so a run
 * contributes its outer shell rather than every internal face stacked through it; and the batch is
 * culled and depth-writing, drawn nearest-first, so the closest surface wins the pixel.</p>
 *
 * <p><b>Drawn, never stamped.</b> No block is placed here. Standing structures up is the server's
 * half and is driven by the same declaration, so the two cannot disagree about what belongs.</p>
 *
 * <p>The cells come from {@link BuilderStructureScan}, which resolves them on a tick cadence — this
 * only replays the result, because resolving a template reads NBT through a synchronized store.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BuilderStructureRenderer {

    /** Textured, translucent, culled and depth-writing — see the class note. */
    private static final RenderType GHOST = RenderType.entityTranslucentCull(InventoryMenu.BLOCK_ATLAS);

    /** Pale blue over the block's own texture, so a ghost still reads as a ghost. */
    private static final float TINT_R = 0.80F;
    private static final float TINT_G = 0.86F;
    private static final float TINT_B = 1.00F;

    /**
     * Base opacity — solid enough to read the material, faint enough never to be mistaken for one.
     *
     * <p>Scaled by each placement's own fade, which is how a room's outer rings thin out toward the
     * edge of the window instead of stopping at a line the real space does not have.</p>
     */
    private static final float ALPHA = 0.6F;

    private BuilderStructureRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        List<BuilderStructureScan.Batch> snapshot = BuilderStructureScan.batches();
        if (snapshot.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return;
        }

        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffer.getBuffer(GHOST);
        BlockRenderDispatcher blocks = mc.getBlockRenderer();
        RandomSource random = RandomSource.create();

        // Nearest first, so the closest translucent surface wins the pixel and nothing ghosted
        // renders through anything else ghosted.
        List<BuilderStructureScan.Batch> ordered = new ArrayList<>(snapshot);
        ordered.sort(Comparator.comparingDouble(b -> b.distanceSqr(cam)));

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        for (BuilderStructureScan.Batch batch : ordered) {
            drawBatch(ps, vc, blocks, level, random, batch);
        }
        ps.popPose();
        buffer.endBatch(GHOST);
    }

    private static void drawBatch(PoseStack ps, VertexConsumer vc, BlockRenderDispatcher blocks,
                                  Level level, RandomSource random,
                                  BuilderStructureScan.Batch batch) {
        float alpha = ALPHA * batch.fade();
        for (Map.Entry<BlockPos, BlockState> entry : batch.cells().entrySet()) {
            BlockPos local = entry.getKey();
            BlockPos world = batch.origin().offset(local);

            ps.pushPose();
            ps.translate(world.getX(), world.getY(), world.getZ());
            drawGhost(ps, vc, blocks, entry.getValue(), local, batch.cells(),
                    LevelRenderer.getLightColor(level, world), random, alpha);
            ps.popPose();
        }
    }

    /**
     * One ghost block, from its own model.
     *
     * <p>The unculled quads always go in — that is where a non-cube keeps its geometry, so a rail or
     * a lamp drawn without them would be nothing at all. The per-face quads go in only where the
     * neighbouring cell is absent, which is what keeps the inside of a mass from being drawn.</p>
     */
    private static void drawGhost(PoseStack ps, VertexConsumer vc, BlockRenderDispatcher blocks,
                                  BlockState state, BlockPos local,
                                  Map<BlockPos, BlockState> neighbours, int light,
                                  RandomSource random, float alpha) {
        BakedModel model = blocks.getBlockModel(state);
        long seed = state.getSeed(local);

        random.setSeed(seed);
        putQuads(ps, vc, model.getQuads(state, null, random, ModelData.EMPTY, null), light, alpha);

        for (Direction dir : Direction.values()) {
            if (neighbours.containsKey(local.relative(dir))) {
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
