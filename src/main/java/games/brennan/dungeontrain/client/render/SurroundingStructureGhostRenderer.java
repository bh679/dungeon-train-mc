package games.brennan.dungeontrain.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.surrounding.SurroundingStructureCategory;
import games.brennan.dungeontrain.net.SurroundingStructureGhostPacket;
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
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the structures around the template you're editing, as textured ghosts.
 *
 * <p>What the server resolved for each {@link SurroundingStructureCategory} arrives over
 * {@link SurroundingStructureGhostPacket} and is cached here per category; the render pass only
 * replays it. Nothing is stamped — that is what keeps these out of the saved template, out of the
 * dirty check, and out of reach of the block protection. The SOLID mode is the one that writes real
 * blocks, and it does so server-side.</p>
 *
 * <p><b>Textured, from each cell's own block model</b>, the same way {@link
 * games.brennan.dungeontrain.client.builder.BuilderRoomGhostRenderer} draws a room's repeats: a track
 * ghosts as its own rails and sleepers, a pillar as its own stone. What you are judging is how the
 * template you're building sits against real neighbouring structures, and flat silhouette boxes read
 * as abstract shapes hanging in the air instead. Two rules keep a mass of them legible:</p>
 * <ul>
 *   <li>a face is drawn only when the neighbouring cell of the <em>same category</em> is absent, so a
 *       run of track contributes its outer shell rather than every internal face stacked through it;</li>
 *   <li>the batch is culled and depth-writing ({@link RenderType#entityTranslucentCull}) and cells are
 *       drawn nearest-first, so the closest surface wins the pixel.</li>
 * </ul>
 *
 * <p>Each category carries its own tint so overlapping previews — track below, pillars beside it —
 * stay tellable apart while still showing their own texture underneath.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class SurroundingStructureGhostRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Textured, translucent, culled and depth-writing — see the class note. */
    private static final RenderType GHOST = RenderType.entityTranslucentCull(InventoryMenu.BLOCK_ATLAS);

    /**
     * Hard ceiling on the cells drawn for a frame, across every category.
     *
     * <p>A dimension-transition band repeats a tunnel segment a long way down the line, so an
     * unbounded resolve is six figures of block models. The server chunks what it sends; this is the
     * client-side backstop under it.</p>
     */
    private static final int MAX_CELLS = 40_000;

    /** Opacity — enough to read the block's own texture, light enough to never be mistaken for real. */
    private static final float ALPHA = 0.65F;

    /**
     * Cached per category, keyed by position so it serves both the draw and the face-culling lookup.
     * Written from the network thread, read from the render thread — replaced wholesale rather than
     * mutated in place, so a frame always sees one complete snapshot.
     */
    private static volatile Map<SurroundingStructureCategory, Map<BlockPos, BlockState>> cache =
            new EnumMap<>(SurroundingStructureCategory.class);

    private static volatile boolean loggedCap = false;

    private SurroundingStructureGhostRenderer() {}

    /** One RGB tint per category so overlapping previews are distinguishable through their own texture. */
    private static float[] tintFor(SurroundingStructureCategory category) {
        return switch (category) {
            case TRACK -> new float[]{0.62f, 0.82f, 1.00f};
            case PILLAR -> new float[]{0.82f, 0.72f, 1.00f};
            case FLATBED_END -> new float[]{1.00f, 0.96f, 0.64f};
            case CARRIAGE_SHELL -> new float[]{0.70f, 1.00f, 0.78f};
            case CARRIAGE_INTERIOR_WALL -> new float[]{1.00f, 0.72f, 0.82f};
        };
    }

    /**
     * Fold a received snapshot into the cache.
     *
     * <p>{@code replace} marks the first packet of a category's snapshot; the chunks that follow
     * append to it, which is how a resolve larger than one packet arrives intact.</p>
     */
    public static void applySnapshot(SurroundingStructureGhostPacket packet) {
        Map<SurroundingStructureCategory, Map<BlockPos, BlockState>> next = new EnumMap<>(cache);
        if (packet.replace()) {
            if (packet.entries().isEmpty()) {
                next.remove(packet.category());
                cache = next;
                return;
            }
            next.put(packet.category(), toMap(packet.entries(), null));
        } else {
            next.put(packet.category(), toMap(packet.entries(), next.get(packet.category())));
        }
        cache = next;
    }

    private static Map<BlockPos, BlockState> toMap(List<SurroundingStructureGhostPacket.Entry> entries,
                                                   Map<BlockPos, BlockState> existing) {
        Map<BlockPos, BlockState> out =
                existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
        for (SurroundingStructureGhostPacket.Entry entry : entries) {
            if (entry.state().isAir()) {
                continue;
            }
            out.put(entry.pos(), entry.state());
        }
        return out;
    }

    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        cache = new EnumMap<>(SurroundingStructureCategory.class);
        loggedCap = false;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Map<SurroundingStructureCategory, Map<BlockPos, BlockState>> snapshot = cache;
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

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);

        int drawn = 0;
        boolean capped = false;
        for (Map.Entry<SurroundingStructureCategory, Map<BlockPos, BlockState>> catEntry
                : snapshot.entrySet()) {
            Map<BlockPos, BlockState> cells = catEntry.getValue();
            if (cells.isEmpty()) {
                continue;
            }
            float[] tint = tintFor(catEntry.getKey());

            // Nearest first: the batch writes depth, so whichever fragment lands first wins the pixel.
            // Sorted per frame because the ordering is the camera's, not the world's.
            List<BlockPos> ordered = new ArrayList<>(cells.keySet());
            ordered.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(cam)));

            for (BlockPos pos : ordered) {
                if (drawn >= MAX_CELLS) {
                    capped = true;
                    break;
                }
                drawn++;
                ps.pushPose();
                ps.translate(pos.getX(), pos.getY(), pos.getZ());
                drawGhost(ps, vc, blocks, cells.get(pos), pos, cells,
                        LevelRenderer.getLightColor(level, pos), random, tint);
                ps.popPose();
            }
            if (capped) {
                break;
            }
        }

        ps.popPose();
        buffer.endBatch(GHOST);

        // Never silently: a truncated draw shows neighbouring structures with holes in them, and a
        // builder would read that as the structure having holes rather than the ghost giving up.
        if (capped && !loggedCap) {
            LOGGER.info("[DungeonTrain] Surrounding structure ghosts hit the {}-cell cap — "
                    + "the preview is drawn partially", MAX_CELLS);
            loggedCap = true;
        } else if (!capped) {
            loggedCap = false;
        }
    }

    /**
     * One ghost block, from its own model.
     *
     * <p>The unculled quads always go in — that is where a non-cube keeps its geometry, so a rail or a
     * lantern drawn without them would be nothing at all. The per-face quads go in only where the
     * neighbouring cell is absent, which is what keeps the inside of a mass from being drawn.</p>
     */
    private static void drawGhost(PoseStack ps, VertexConsumer vc, BlockRenderDispatcher blocks,
                                  BlockState state, BlockPos pos, Map<BlockPos, BlockState> neighbours,
                                  int light, RandomSource random, float[] tint) {
        BakedModel model = blocks.getBlockModel(state);
        long seed = state.getSeed(pos);

        random.setSeed(seed);
        putQuads(ps, vc, model.getQuads(state, null, random, ModelData.EMPTY, null), light, tint);

        for (Direction dir : Direction.values()) {
            if (neighbours.containsKey(pos.relative(dir))) {
                continue;
            }
            random.setSeed(seed);
            putQuads(ps, vc, model.getQuads(state, dir, random, ModelData.EMPTY, null), light, tint);
        }
    }

    private static void putQuads(PoseStack ps, VertexConsumer vc, List<BakedQuad> quads,
                                 int light, float[] tint) {
        for (BakedQuad quad : quads) {
            vc.putBulkData(ps.last(), quad, tint[0], tint[1], tint[2], ALPHA, light,
                    OverlayTexture.NO_OVERLAY);
        }
    }
}
