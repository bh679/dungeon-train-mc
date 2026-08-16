package games.brennan.dungeontrain.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.surrounding.SurroundingStructureCategory;
import games.brennan.dungeontrain.net.SurroundingStructureGhostPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side renderer for the Surrounding Structures GHOST preview. Caches the last
 * {@link SurroundingStructureGhostPacket} snapshot per {@link SurroundingStructureCategory} and draws
 * a translucent wireframe box over every entry each frame.
 *
 * <p>First-version rendering: a colored line-box outline per ghost block via
 * {@link LevelRenderer#renderLineBox} rather than a full translucent block model — simple, guaranteed
 * to compile against every block's render shape, and reads clearly as "preview, not real" without
 * needing per-block model/texture lookups. A solid translucent fill can replace this later without
 * touching the packet or manager.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class SurroundingStructureGhostRenderer {

    private static final Map<SurroundingStructureCategory, List<SurroundingStructureGhostPacket.Entry>> CACHE =
        new EnumMap<>(SurroundingStructureCategory.class);

    /** One RGB per category so overlapping previews (e.g. TRACK below, PILLAR beside) are distinguishable. */
    private static float[] colorFor(SurroundingStructureCategory category) {
        return switch (category) {
            case TRACK -> new float[]{0.35f, 0.75f, 1.0f};
            case PILLAR -> new float[]{0.75f, 0.55f, 1.0f};
            case DIMENSION_REPEAT -> new float[]{1.0f, 0.65f, 0.35f};
            case FLATBED_END -> new float[]{1.0f, 0.9f, 0.3f};
            case CARRIAGE_SHELL -> new float[]{0.4f, 1.0f, 0.55f};
            case CARRIAGE_INTERIOR_WALL -> new float[]{1.0f, 0.4f, 0.6f};
        };
    }

    private SurroundingStructureGhostRenderer() {}

    public static void applySnapshot(SurroundingStructureGhostPacket packet) {
        if (packet.entries().isEmpty() && packet.replace()) {
            CACHE.remove(packet.category());
            return;
        }
        if (packet.replace()) {
            CACHE.put(packet.category(), new java.util.ArrayList<>(packet.entries()));
        } else {
            CACHE.computeIfAbsent(packet.category(), k -> new java.util.ArrayList<>())
                .addAll(packet.entries());
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        CACHE.clear();
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (CACHE.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer consumer = buffer.getBuffer(RenderType.lines());

        for (Map.Entry<SurroundingStructureCategory, List<SurroundingStructureGhostPacket.Entry>> catEntry
                : CACHE.entrySet()) {
            float[] color = colorFor(catEntry.getKey());
            for (SurroundingStructureGhostPacket.Entry entry : catEntry.getValue()) {
                BlockState state = entry.state();
                if (state.isAir()) continue;
                BlockPos pos = entry.pos();
                double minX = pos.getX() - cam.x;
                double minY = pos.getY() - cam.y;
                double minZ = pos.getZ() - cam.z;
                LevelRenderer.renderLineBox(ps, consumer,
                    minX + 0.02, minY + 0.02, minZ + 0.02,
                    minX + 0.98, minY + 0.98, minZ + 0.98,
                    color[0], color[1], color[2], 0.55f);
            }
        }

        buffer.endBatch(RenderType.lines());
    }
}
