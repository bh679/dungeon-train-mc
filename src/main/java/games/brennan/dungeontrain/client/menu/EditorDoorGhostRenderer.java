package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.EditorDoorGhostsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * World-space overlay that paints an amber ghost cube on each cell the two portal corridor doors
 * occupy at a portal-room editor plot — the openings an author has to build the room around.
 *
 * <p><b>Amber, not the strays' red.</b> {@link EditorStrayGhostRenderer} washes a block that should
 * not be there; this marks a place where something <i>will</i> be there. Red reads as a mistake and
 * would be a lie here, so the two overlays are told apart by colour as well as by position: a red
 * cube means "remove this", an amber one means "leave this alone". They are also independently
 * toggleable, so both can be up at once and still be read.</p>
 *
 * <p>A full translucent cube with the outline on top, matching the strays' treatment — the doorway is
 * a volume the author must keep clear rather than a property of a block that is already there, and a
 * thin wireframe against a dark room wall is easy to miss from across the plot.</p>
 *
 * <p>Driven by {@link EditorDoorGhostsPacket}: the server pushes an absolute-position snapshot when
 * the plot grid moves — a resize, a new room, a deletion — or when the player toggles the ghosts, and
 * an empty snapshot clears the cache. Positions are absolute because a door cell sits one column
 * outside its plot and so has no plot-local origin to be relative to.</p>
 */
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class EditorDoorGhostRenderer {

    /** Outset on every axis so the ghost sits just proud of the cell rather than z-fighting with it. */
    private static final double EXPAND = 0.004;

    /** How far a ghost is drawn from the camera, in chunks. Matches the strays' cull. */
    private static final int MAX_DISTANCE_CHUNKS = 4;

    /**
     * Beyond this (squared) distance from the camera a ghost is skipped, bounding the per-frame box
     * count. A door further out is still known client-side — it paints as soon as you fly into range
     * — so the cull costs visibility at distance, never correctness.
     */
    private static final double MAX_DISTANCE_SQ =
        (MAX_DISTANCE_CHUNKS * 16.0) * (MAX_DISTANCE_CHUNKS * 16.0);

    private static final float RED = 0.95f;
    private static final float GREEN = 0.75f;
    private static final float BLUE = 0.20f;

    /** Alpha of the wash. Low enough to read the block behind it, high enough to be unmissable. */
    private static final float FILL_ALPHA = 0.35f;

    /** Most recent snapshot from the server. Empty → renderer is a no-op. */
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

        VertexConsumer fill = buffer.getBuffer(RenderType.debugFilledBox());
        for (BlockPos pos : snapshot) {
            if (pos.distToCenterSqr(cam.x, cam.y, cam.z) > MAX_DISTANCE_SQ) continue;
            LevelRenderer.addChainedFilledBoxVertices(ps, fill,
                pos.getX() - EXPAND, pos.getY() - EXPAND, pos.getZ() - EXPAND,
                pos.getX() + 1.0 + EXPAND, pos.getY() + 1.0 + EXPAND, pos.getZ() + 1.0 + EXPAND,
                RED, GREEN, BLUE, FILL_ALPHA);
        }
        buffer.endBatch(RenderType.debugFilledBox());

        VertexConsumer lines = buffer.getBuffer(RenderType.lines());
        for (BlockPos pos : snapshot) {
            if (pos.distToCenterSqr(cam.x, cam.y, cam.z) > MAX_DISTANCE_SQ) continue;
            lines(ps, lines, pos);
        }
        buffer.endBatch(RenderType.lines());

        ps.popPose();
    }

    private static void lines(PoseStack ps, VertexConsumer vc, BlockPos pos) {
        LevelRenderer.renderLineBox(ps, vc,
            new AABB(
                pos.getX() - EXPAND, pos.getY() - EXPAND, pos.getZ() - EXPAND,
                pos.getX() + 1.0 + EXPAND, pos.getY() + 1.0 + EXPAND, pos.getZ() + 1.0 + EXPAND),
            RED, GREEN, BLUE, 1.0f);
    }
}
