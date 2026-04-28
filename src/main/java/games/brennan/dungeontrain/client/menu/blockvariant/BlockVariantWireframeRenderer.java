package games.brennan.dungeontrain.client.menu.blockvariant;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.BlockVariantOutlinePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;

import java.util.HashSet;
import java.util.Set;

/**
 * World-space overlay that draws a thin opaque white wireframe around every
 * variant-flagged cell in the editor plot the player is standing in. Replaces
 * the END_ROD particle outline that used to live in
 * {@link games.brennan.dungeontrain.editor.VariantOverlayRenderer} — line
 * rendering is GPU-batched and constant-cost per frame regardless of how many
 * flagged cells are grouped together, so dense plots no longer tank framerate.
 *
 * <p>Driven by {@link BlockVariantOutlinePacket}: server pushes a snapshot of
 * flagged local positions whenever the player enters / leaves a plot,
 * toggles the editor overlay, or any cell's flag set changes. Client caches
 * that snapshot and renders during
 * {@link RenderLevelStageEvent.Stage#AFTER_TRANSLUCENT_BLOCKS}. An empty
 * snapshot clears the cache.</p>
 */
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class BlockVariantWireframeRenderer {

    /** Outset on every axis to keep the wireframe clear of block face z-fighting. */
    private static final double EXPAND = 0.001;

    /** Most recent snapshot from the server. Empty set → renderer is a no-op. */
    private static final Set<BlockPos> CACHE = new HashSet<>();
    private static volatile BlockPos cacheOrigin = BlockPos.ZERO;

    private BlockVariantWireframeRenderer() {}

    /** Called from the packet handler on the client thread. */
    public static synchronized void applySnapshot(BlockVariantOutlinePacket packet) {
        CACHE.clear();
        if (packet.isEmpty()) {
            cacheOrigin = BlockPos.ZERO;
            return;
        }
        cacheOrigin = packet.plotOriginWorldPos();
        CACHE.addAll(packet.positions());
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (CACHE.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffer.getBuffer(RenderType.lines());

        Set<BlockPos> snapshot;
        BlockPos origin;
        synchronized (BlockVariantWireframeRenderer.class) {
            snapshot = new HashSet<>(CACHE);
            origin = cacheOrigin;
        }

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        for (BlockPos local : snapshot) {
            BlockPos world = origin.offset(local);
            double x0 = world.getX() - EXPAND;
            double y0 = world.getY() - EXPAND;
            double z0 = world.getZ() - EXPAND;
            double x1 = world.getX() + 1.0 + EXPAND;
            double y1 = world.getY() + 1.0 + EXPAND;
            double z1 = world.getZ() + 1.0 + EXPAND;
            LevelRenderer.renderLineBox(ps, vc,
                new AABB(x0, y0, z0, x1, y1, z1),
                1.0f, 1.0f, 1.0f, 1.0f);
        }
        ps.popPose();

        buffer.endBatch(RenderType.lines());
    }
}
