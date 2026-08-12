package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws what surrounds an open track template — the rest of the line, and the rest of a pillar
 * column — as translucent ghosts of the real blocks.
 *
 * <p>A track tile is four blocks of a line that runs three hundred, and a pillar section is a slab
 * whose whole job is to hold a line up. Neither tells you much on its own; what you are judging is
 * the join. So the whole stretch of line is drawn back in around it, and the plot is left as the
 * only solid thing in it.</p>
 *
 * <p><b>Drawn, never stamped</b> — the corridor is erased on the way into a track build
 * ({@code BuilderWorldSetup.applyOpenTrack}), so a ghost cannot be broken, walked on, or saved into
 * the template, because there is nothing there. The same choice {@code BuilderGhostTrainRenderer}
 * makes for the rest of the carriage group.</p>
 *
 * <p>Textured, from each cell's own block model, which is why the cells carry a {@link BlockState}.
 * A rail ghosts as a rail and stone brick ghosts as stone brick — flat silhouette quads read as an
 * abstract shape hanging in the air, and the whole point here is to judge a real join against real
 * blocks. Two rules keep a mass of them legible:</p>
 * <ul>
 *   <li>a face is only drawn when the neighbouring cell is <em>absent</em>, so a run contributes its
 *       outer shell rather than every internal face stacked through it;</li>
 *   <li>the batch is culled and depth-writing ({@link RenderType#entityTranslucentCull}) and drawn
 *       front-to-back, so the nearest surface wins the pixel and nothing ghosted renders behind
 *       anything else ghosted. The opaque world was drawn before this pass, so solid geometry behind
 *       a ghost still shows through the blend.</li>
 * </ul>
 *
 * <p>The scene itself is {@link BuilderTrackSceneGhosts}' — the line, the columns under it, the arch
 * between them and the staircases climbing to it, laid out by the generator's own rules and built
 * from the authored templates rather than from a stand-in palette.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BuilderTrackGhostRenderer {

    /** Textured, translucent, culled and depth-writing — see the class note. */
    private static final RenderType GHOST = RenderType.entityTranslucentCull(InventoryMenu.BLOCK_ATLAS);

    /**
     * Ticks between sweeps.
     *
     * <p>Short, because the ghosts of the open template are built from the plot's live blocks and a
     * builder placing one expects the copies down the line to follow. A quarter of a second reads as
     * immediate; a full second reads as broken.</p>
     */
    private static final int RESCAN_INTERVAL_TICKS = 5;
    /** Hard ceiling so a pathological build can't stall a frame. */
    private static final int MAX_CELLS = 40_000;

    /** Pale blue over the block's own texture, so a ghost still reads as a ghost. */
    private static final float TINT_R = 0.80F;
    private static final float TINT_G = 0.86F;
    private static final float TINT_B = 1.00F;
    /** Half opacity — solid enough to read the material, faint enough never to be mistaken for one. */
    private static final float ALPHA = 0.5F;

    /** Cached ghost cells, replaced wholesale so a render pass never sees a partial sweep. */
    private static volatile Map<BlockPos, BlockState> cells = Map.of();
    private static int tickCounter = 0;

    private BuilderTrackGhostRenderer() {}

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        if (++tickCounter < RESCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        rescan();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        cells = Map.of();
        // The next world reads its templates from its own config directory, and a kept entry would
        // ghost one world's track into another's.
        BuilderGhostTemplates.clear();
    }

    private static void rescan() {
        Minecraft mc = Minecraft.getInstance();
        TrackKind kind = ClientTrackGhost.kind();
        BoundingBox plot = ClientTrackGhost.plot();
        if (kind == null || plot == null || mc.level == null) {
            cells = Map.of();
            return;
        }
        // Resolved here rather than in the render pass: the first read of a template does file I/O
        // and NBT decompression inside a synchronized store, which is not a thing to do per frame.
        Map<BlockPos, BlockState> scene = BuilderTrackSceneGhosts.build(
                BuilderGhostTemplates.worldSeed(), CarriageDims.DEFAULT, plot,
                kind, BuilderBoundsState.buildName(), livePlotCells(mc.level, plot));
        cells = scene.size() <= MAX_CELLS ? scene : trimmed(scene);
    }

    /**
     * The plot's blocks as they are right now, local to its own origin.
     *
     * <p>This is what makes the preview live: every ghost showing the same template reads these
     * rather than the copy on disk, so breaking a block on your tile breaks it in every tile down
     * the line as you do it. Reading the saved file instead would show the last save, which is the
     * one thing a preview must never do — quietly disagree with the work in front of you.</p>
     */
    private static Map<BlockPos, BlockState> livePlotCells(Level level, BoundingBox plot) {
        Map<BlockPos, BlockState> local = new LinkedHashMap<>();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int x = plot.minX(); x <= plot.maxX(); x++) {
            for (int y = plot.minY(); y <= plot.maxY(); y++) {
                for (int z = plot.minZ(); z <= plot.maxZ(); z++) {
                    BlockState state = level.getBlockState(probe.set(x, y, z));
                    if (!state.isAir()) {
                        local.put(new BlockPos(x - plot.minX(), y - plot.minY(), z - plot.minZ()),
                                state);
                    }
                }
            }
        }
        return local;
    }

    /**
     * Drop the far end of an over-large scene.
     *
     * <p>The cap is a frame-time guard, and the cells worth keeping are the ones nearest the plot —
     * which is where the builder is standing and what the whole preview is about.</p>
     */
    private static Map<BlockPos, BlockState> trimmed(Map<BlockPos, BlockState> scene) {
        BoundingBox plot = ClientTrackGhost.plot();
        int centreX = plot == null ? 0 : (plot.minX() + plot.maxX()) / 2;
        return scene.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> Math.abs(e.getKey().getX() - centreX)))
                .limit(MAX_CELLS)
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll);
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Map<BlockPos, BlockState> snapshot = cells;
        if (snapshot.isEmpty() || !ClientTrackGhost.isActive()) {
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

        // Front-to-back: the batch writes depth, so whichever fragment lands first wins the pixel
        // and everything behind it is dropped — which is only the answer you want if the nearest one
        // got there first. Sorted per frame because the ordering is the camera's, not the world's.
        List<BlockPos> ordered = new ArrayList<>(snapshot.keySet());
        ordered.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(cam)));

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        for (BlockPos pos : ordered) {
            BlockState state = snapshot.get(pos);
            ps.pushPose();
            ps.translate(pos.getX(), pos.getY(), pos.getZ());
            drawGhost(ps, vc, blocks, state, pos, snapshot, LevelRenderer.getLightColor(level, pos),
                    random);
            ps.popPose();
        }
        ps.popPose();
        buffer.endBatch(GHOST);
    }

    /**
     * One ghost block, from its own model.
     *
     * <p>The unculled quads always go in — that is where a non-cube keeps its geometry, so a rail
     * drawn without them would be nothing at all. The per-face quads go in only where the
     * neighbouring cell is absent, which is what keeps the inside of a run from being drawn.</p>
     */
    private static void drawGhost(PoseStack ps, VertexConsumer vc, BlockRenderDispatcher blocks,
                                  BlockState state, BlockPos pos, Map<BlockPos, BlockState> cells,
                                  int light, RandomSource random) {
        BakedModel model = blocks.getBlockModel(state);
        long seed = state.getSeed(pos);

        random.setSeed(seed);
        putQuads(ps, vc, model.getQuads(state, null, random, ModelData.EMPTY, null), light);

        for (Direction dir : Direction.values()) {
            if (cells.containsKey(pos.relative(dir))) {
                continue;
            }
            random.setSeed(seed);
            putQuads(ps, vc, model.getQuads(state, dir, random, ModelData.EMPTY, null), light);
        }
    }

    private static void putQuads(PoseStack ps, VertexConsumer vc,
                                 List<net.minecraft.client.renderer.block.model.BakedQuad> quads,
                                 int light) {
        for (net.minecraft.client.renderer.block.model.BakedQuad quad : quads) {
            vc.putBulkData(ps.last(), quad, TINT_R, TINT_G, TINT_B, ALPHA, light,
                    OverlayTexture.NO_OVERLAY);
        }
    }
}
