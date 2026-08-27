package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.EditorDoorGhostsPacket;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.List;

/**
 * World-space overlay that stands a translucent oak door in each portal corridor doorway at a
 * portal-room editor plot — the openings an author has to build the room around.
 *
 * <p><b>The door itself, not a marker shaped like one.</b> The state rendered is
 * {@link PortalCarriageBuilder#doorState}, the very state the builder hangs in a real corridor, so
 * the ghost shows the actual facing and hinge an author will meet rather than a lookalike that could
 * drift from it. A plain coloured cube said "something is here"; a door says <i>what</i>, which is
 * the whole question when the thing being authored is the room it opens into.</p>
 *
 * <p><b>Translucent, so it still reads as a ghost.</b> The model is pushed through
 * {@link GhostBuffer}, which caps every vertex's alpha and forces the whole model onto
 * {@link RenderType#translucent()} — a door rendered opaque would be indistinguishable from one an
 * author had actually placed, which is exactly the confusion the ghosts exist to prevent. An amber
 * wireframe boxes each half for the same reason {@link EditorStrayGhostRenderer} outlines its
 * strays: against a dark room wall a translucent model alone is easy to miss from across the plot.
 * Amber rather than the strays' red because these two overlays say opposite things — red means
 * "remove this", amber means "leave this alone".</p>
 *
 * <p>Driven by {@link EditorDoorGhostsPacket}: the server pushes an absolute-position snapshot of
 * each door's <b>lower</b> cell when the plot grid moves — a resize, a new room, a deletion — or when
 * the player toggles the ghosts, and an empty snapshot clears the cache. Positions are absolute
 * because a door stands one column outside its plot and so has no plot-local origin to be relative
 * to.</p>
 */
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class EditorDoorGhostRenderer {

    /** Outset on every axis so the outline sits just proud of the cell rather than z-fighting with it. */
    private static final double EXPAND = 0.004;

    /** How far a ghost is drawn from the camera, in chunks. Matches the strays' cull. */
    private static final int MAX_DISTANCE_CHUNKS = 4;

    /**
     * Beyond this (squared) distance from the camera a ghost is skipped, bounding the per-frame model
     * count. A door further out is still known client-side — it paints as soon as you fly into range
     * — so the cull costs visibility at distance, never correctness.
     */
    private static final double MAX_DISTANCE_SQ =
        (MAX_DISTANCE_CHUNKS * 16.0) * (MAX_DISTANCE_CHUNKS * 16.0);

    private static final float OUTLINE_RED = 0.95f;
    private static final float OUTLINE_GREEN = 0.75f;
    private static final float OUTLINE_BLUE = 0.20f;

    /**
     * Alpha ceiling for the door model, 0..255. High enough to read the wood grain, low enough that
     * the room behind it still shows through and the door cannot be mistaken for a placed block.
     */
    private static final int GHOST_ALPHA = 130;

    /** How many cells tall a door is — the lower cell from the packet, plus the upper above it. */
    private static final int DOOR_HEIGHT = 2;

    /** Most recent snapshot from the server: one lower-cell position per door. Empty → no-op. */
    private static final List<BlockPos> CACHE = new ArrayList<>();

    private EditorDoorGhostRenderer() {}

    /** Called from the packet handler on the client thread. */
    public static synchronized void applySnapshot(EditorDoorGhostsPacket packet) {
        CACHE.clear();
        if (packet.isEmpty()) return;
        CACHE.addAll(packet.positions());
    }

    /**
     * Wipe the cache on world quit so phantom ghosts don't survive across worlds — symmetric with
     * {@link EditorStrayGhostRenderer#onLoggingOut}.
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        applySnapshot(EditorDoorGhostsPacket.empty());
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        List<BlockPos> snapshot;
        synchronized (EditorDoorGhostRenderer.class) {
            if (CACHE.isEmpty()) return;
            snapshot = new ArrayList<>(CACHE);
        }

        Minecraft mc = Minecraft.getInstance();
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);

        BlockRenderDispatcher blocks = mc.getBlockRenderer();
        MultiBufferSource ghost = type -> new GhostBuffer(buffer.getBuffer(RenderType.translucent()));
        for (BlockPos base : snapshot) {
            if (base.distToCenterSqr(cam.x, cam.y, cam.z) > MAX_DISTANCE_SQ) continue;
            for (int half = 0; half < DOOR_HEIGHT; half++) {
                door(ps, blocks, ghost, base.above(half), /*lower*/ half == 0);
            }
        }
        buffer.endBatch(RenderType.translucent());

        VertexConsumer lines = buffer.getBuffer(RenderType.lines());
        for (BlockPos base : snapshot) {
            if (base.distToCenterSqr(cam.x, cam.y, cam.z) > MAX_DISTANCE_SQ) continue;
            for (int half = 0; half < DOOR_HEIGHT; half++) {
                outline(ps, lines, base.above(half));
            }
        }
        buffer.endBatch(RenderType.lines());

        ps.popPose();
    }

    /**
     * Render one half of a door at {@code pos}.
     *
     * <p>Lit at full brightness rather than from the world: the doorway stands in an unlit editor
     * plot at the edge of a bedrock cage, and a ghost that goes black in shadow is a ghost nobody
     * sees. The explicit {@link RenderType#translucent()} is what routes the model's own cutout
     * layer through {@link GhostBuffer} — {@code renderSingleBlock} honours the type it is handed.</p>
     */
    private static void door(PoseStack ps, BlockRenderDispatcher blocks, MultiBufferSource ghost,
                             BlockPos pos, boolean lower) {
        BlockState state = PortalCarriageBuilder.doorState(lower);
        ps.pushPose();
        ps.translate(pos.getX(), pos.getY(), pos.getZ());
        blocks.renderSingleBlock(state, ps, ghost,
            LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
            ModelData.EMPTY, RenderType.translucent());
        ps.popPose();
    }

    private static void outline(PoseStack ps, VertexConsumer vc, BlockPos pos) {
        LevelRenderer.renderLineBox(ps, vc,
            new AABB(
                pos.getX() - EXPAND, pos.getY() - EXPAND, pos.getZ() - EXPAND,
                pos.getX() + 1.0 + EXPAND, pos.getY() + 1.0 + EXPAND, pos.getZ() + 1.0 + EXPAND),
            OUTLINE_RED, OUTLINE_GREEN, OUTLINE_BLUE, 1.0f);
    }

    /**
     * A {@link VertexConsumer} that caps the alpha of everything written through it, so an opaque
     * block model comes out as a ghost.
     *
     * <p>{@code min} rather than an outright overwrite so a model that is already more transparent
     * than {@link #GHOST_ALPHA} stays that way. Every override returns {@code this} rather than the
     * delegate — the vertex builders chain these calls, and handing back the raw delegate would let
     * the rest of the chain write past the cap.</p>
     */
    private record GhostBuffer(VertexConsumer delegate) implements VertexConsumer {

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, Math.min(alpha, GHOST_ALPHA));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float normalX, float normalY, float normalZ) {
            delegate.setNormal(normalX, normalY, normalZ);
            return this;
        }
    }
}
