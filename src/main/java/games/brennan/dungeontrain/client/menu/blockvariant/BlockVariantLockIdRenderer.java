package games.brennan.dungeontrain.client.menu.blockvariant;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.MenuRenderStates;
import games.brennan.dungeontrain.net.BlockVariantLockIdsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Map;

/**
 * World-space overlay that draws each locked cell's lock-id digit on all 6
 * block faces while the player is in an editor plot. Independent of the
 * per-cell {@link BlockVariantMenu} — visible without opening the menu so
 * authors can see lock groups at a glance from any direction.
 *
 * <p>Driven by {@link BlockVariantLockIdsPacket}: server pushes a snapshot
 * of {@code (localPos, lockId>0)} pairs whenever the player enters / leaves
 * a plot or any cell's lock-id changes. Client caches that snapshot and
 * renders during {@link RenderLevelStageEvent.Stage#AFTER_TRANSLUCENT_BLOCKS}.
 * An empty snapshot clears the cache.</p>
 *
 * <p>Face basis matches {@link games.brennan.dungeontrain.editor.BlockVariantMenuController#buildSyncPacket}
 * — vertical faces use world up, horizontal faces use world up — so the
 * digit reads upright from any of the 6 sides.</p>
 */
@Mod.EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    bus = Mod.EventBusSubscriber.Bus.FORGE,
    value = Dist.CLIENT
)
public final class BlockVariantLockIdRenderer {

    private static final RenderType PANEL_QUAD = RenderType.create(
        DungeonTrain.MOD_ID + ":block_variant_lockid_quad",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        256,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(MenuRenderStates.SHADER_POSITION_COLOR)
            .setTransparencyState(MenuRenderStates.TRANSPARENCY_TRANSLUCENT)
            .setCullState(MenuRenderStates.CULL_DISABLED)
            .setDepthTestState(MenuRenderStates.DEPTH_LEQUAL)
            .setWriteMaskState(MenuRenderStates.WRITE_COLOR_ONLY)
            .createCompositeState(false)
    );

    /** World-unit half-side for the badge backdrop quad. 0.18 ≈ 5.7px on a 1m face at typical zoom. */
    private static final double BADGE_HALF = 0.18;
    /** Inset above the block face so the label clears z-fighting with the block surface. */
    private static final double FACE_INSET = 0.51;
    /** Font scale matches BlockVariantMenuRenderer's 0.012 so the digit is comfortably readable on a 1m face. */
    private static final float TEXT_SCALE = 0.018f;

    /** Most recent snapshot from the server. Empty map → renderer is a no-op. */
    private static final Map<BlockPos, Integer> CACHE = new HashMap<>();
    private static volatile BlockPos cacheOrigin = BlockPos.ZERO;

    private BlockVariantLockIdRenderer() {}

    /** Called from the packet handler on the client thread. */
    public static synchronized void applySnapshot(BlockVariantLockIdsPacket packet) {
        CACHE.clear();
        if (packet.isEmpty()) {
            cacheOrigin = BlockPos.ZERO;
            return;
        }
        cacheOrigin = packet.plotOriginWorldPos();
        for (BlockVariantLockIdsPacket.Entry e : packet.entries()) {
            CACHE.put(e.localPos(), e.lockId());
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (CACHE.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        Map<BlockPos, Integer> snapshot;
        BlockPos origin;
        synchronized (BlockVariantLockIdRenderer.class) {
            snapshot = new HashMap<>(CACHE);
            origin = cacheOrigin;
        }

        for (Map.Entry<BlockPos, Integer> e : snapshot.entrySet()) {
            BlockPos local = e.getKey();
            int lockId = e.getValue();
            if (lockId <= 0) continue;
            BlockPos world = origin.offset(local);
            String label = Integer.toString(lockId);
            for (Direction face : Direction.values()) {
                drawLabelOnFace(ps, buffer, font, cam, world, face, label);
            }
        }

        buffer.endBatch(PANEL_QUAD);
    }

    private static void drawLabelOnFace(PoseStack ps, MultiBufferSource buffer, Font font,
                                        Vec3 cam, BlockPos world, Direction face, String label) {
        Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        Vec3 anchor = new Vec3(
            world.getX() + 0.5 + face.getStepX() * FACE_INSET,
            world.getY() + 0.5 + face.getStepY() * FACE_INSET,
            world.getZ() + 0.5 + face.getStepZ() * FACE_INSET);
        Vec3 up;
        if (face.getAxis() == Direction.Axis.Y) {
            // Top / bottom faces — pick a stable horizontal up so the digit
            // doesn't flip as the player rotates. World +Z (south) is fine.
            up = new Vec3(0, 0, 1);
        } else {
            up = new Vec3(0, 1, 0);
        }
        Vec3 right = up.cross(normal).normalize();

        ps.pushPose();
        ps.translate(anchor.x - cam.x, anchor.y - cam.y, anchor.z - cam.z);
        Matrix3f basis = new Matrix3f(
            (float) right.x, (float) right.y, (float) right.z,
            (float) up.x, (float) up.y, (float) up.z,
            (float) normal.x, (float) normal.y, (float) normal.z
        );
        ps.mulPose(new Quaternionf().setFromNormalized(basis));

        // Tinted backdrop so the digit reads against any block colour.
        drawQuad(ps, buffer, -BADGE_HALF, -BADGE_HALF, BADGE_HALF, BADGE_HALF, 0xC0202020);

        // Digit, centered.
        ps.pushPose();
        ps.translate(0, 0, 0.001f);
        ps.scale(TEXT_SCALE, -TEXT_SCALE, TEXT_SCALE);
        int textWidth = font.width(label);
        Matrix4f mat = ps.last().pose();
        font.drawInBatch(label,
            -textWidth / 2f, -font.lineHeight / 2f,
            0xFFFFEEBB, false, mat, buffer,
            Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT);
        ps.popPose();
        ps.popPose();
    }

    private static void drawQuad(PoseStack ps, MultiBufferSource buffer,
                                 double x1, double y1, double x2, double y2, int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        VertexConsumer vc = buffer.getBuffer(PANEL_QUAD);
        Matrix4f mat = ps.last().pose();
        vc.vertex(mat, (float) x1, (float) y1, 0).color(r, g, b, a).endVertex();
        vc.vertex(mat, (float) x2, (float) y1, 0).color(r, g, b, a).endVertex();
        vc.vertex(mat, (float) x2, (float) y2, 0).color(r, g, b, a).endVertex();
        vc.vertex(mat, (float) x1, (float) y2, 0).color(r, g, b, a).endVertex();
    }
}
