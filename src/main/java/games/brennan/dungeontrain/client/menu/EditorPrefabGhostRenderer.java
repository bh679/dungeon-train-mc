package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.vertex.PoseStack;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.EditorPrefabGhostsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.RenderShape;
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
 * Draws the prefab ghosts — the blocks a bound prefab anchor will stamp — as translucent block
 * models in the editor, so a parent template is built around the real footprint rather than a
 * purple marker.
 *
 * <p>The door ghosts' renderer with the door swapped for whatever block the server says: every cell
 * of {@link EditorPrefabGhostsPacket} is rendered through the shared {@link GhostBuffer} alpha cap
 * on {@link RenderType#translucent()}, lit full-bright (the plot is an unlit box under the sky
 * plane), and distance-culled so a grid full of anchors stays cheap.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class EditorPrefabGhostRenderer {

    /** Alpha ceiling, 0..255 — a touch below the door ghosts so a whole design reads as a sketch. */
    private static final int GHOST_ALPHA = 110;

    private static final int MAX_DISTANCE_CHUNKS = 4;
    private static final double MAX_DISTANCE_SQ = (MAX_DISTANCE_CHUNKS * 16.0) * (MAX_DISTANCE_CHUNKS * 16.0);

    private static final List<EditorPrefabGhostsPacket.Ghost> CACHE = new ArrayList<>();

    private EditorPrefabGhostRenderer() {}

    /** Called from the packet handler on the client thread. */
    public static synchronized void applySnapshot(EditorPrefabGhostsPacket packet) {
        CACHE.clear();
        if (packet.isEmpty()) return;
        CACHE.addAll(packet.ghosts());
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        applySnapshot(EditorPrefabGhostsPacket.empty());
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        List<EditorPrefabGhostsPacket.Ghost> snapshot;
        synchronized (EditorPrefabGhostRenderer.class) {
            if (CACHE.isEmpty()) return;
            snapshot = new ArrayList<>(CACHE);
        }

        Minecraft mc = Minecraft.getInstance();
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        BlockRenderDispatcher blocks = mc.getBlockRenderer();
        MultiBufferSource ghost = type -> new GhostBuffer(buffer.getBuffer(RenderType.translucent()), GHOST_ALPHA);

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        for (EditorPrefabGhostsPacket.Ghost g : snapshot) {
            if (g.pos().distToCenterSqr(cam.x, cam.y, cam.z) > MAX_DISTANCE_SQ) continue;
            // Block-entity renderers (chests, signs) are not part of a block model; their base
            // model, if any, still draws — a chest ghost is a plain box, which is enough of a hint.
            if (g.state().getRenderShape() != RenderShape.MODEL) continue;
            ps.pushPose();
            ps.translate(g.pos().getX(), g.pos().getY(), g.pos().getZ());
            blocks.renderSingleBlock(g.state(), ps, ghost,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                ModelData.EMPTY, RenderType.translucent());
            ps.popPose();
        }
        buffer.endBatch(RenderType.translucent());
        ps.popPose();
    }
}
