package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.EditorStrayBlocksPacket;
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
 * World-space overlay that paints a red ghost cube over every block sitting outside the editor's
 * template plots — the blocks a save would silently drop.
 *
 * <p>Deliberately a full translucent cube rather than the thin white wireframe
 * {@link games.brennan.dungeontrain.client.menu.blockvariant.BlockVariantWireframeRenderer} draws
 * for variant cells: those mark a property of a block that belongs in the build, while these mark
 * a block that does not belong at all. A wireframe reads as annotation; a red wash reads as a
 * mistake, which is what it is. The outline goes on top of the wash so a single stray against a
 * dark backdrop is still obvious.</p>
 *
 * <p>Driven by {@link EditorStrayBlocksPacket}: the server pushes an absolute-position snapshot
 * whenever its sweep finds a change or the player toggles the ghosts, and an empty snapshot clears
 * the cache. Positions are absolute because a stray belongs to no plot and so has no local
 * origin to be relative to.</p>
 */
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class EditorStrayGhostRenderer {

    /** Outset on every axis so the ghost sits just proud of the block rather than z-fighting with it. */
    private static final double EXPAND = 0.004;

    /** Beyond this (squared) distance from the camera a ghost is skipped, bounding the per-frame box count. */
    private static final double MAX_DISTANCE_SQ = 96.0 * 96.0;

    private static final float RED = 0.90f;
    private static final float GREEN = 0.15f;
    private static final float BLUE = 0.15f;

    /** Alpha of the wash. Low enough to read the block underneath, high enough to be unmissable. */
    private static final float FILL_ALPHA = 0.35f;

    /** Most recent snapshot from the server. Empty → renderer is a no-op. */
    private static final List<BlockPos> CACHE = new ArrayList<>();

    private EditorStrayGhostRenderer() {}

    /** Called from the packet handler on the client thread. */
    public static synchronized void applySnapshot(EditorStrayBlocksPacket packet) {
        CACHE.clear();
        if (packet.isEmpty()) return;
        CACHE.addAll(packet.positions());
    }

    /**
     * Wipe the cache on world quit so phantom ghosts don't survive across worlds — symmetric with
     * {@link EditorPlotLabelsRenderer#onLoggingOut}.
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        applySnapshot(EditorStrayBlocksPacket.empty());
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        List<BlockPos> snapshot;
        synchronized (EditorStrayGhostRenderer.class) {
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
