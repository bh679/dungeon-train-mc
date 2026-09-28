package games.brennan.dungeontrain.client.menu.blockvariant;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.MenuRenderStates;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.BlockVariantLockIdsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
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
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
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

    /**
     * Wipe the lock-id cache on world quit so phantom digits don't persist
     * across worlds. Symmetric with
     * {@link games.brennan.dungeontrain.client.menu.EditorPlotLabelsRenderer#onLoggingOut}.
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        applySnapshot(BlockVariantLockIdsPacket.empty());
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

        // Only the faces an author can actually read: toward the camera, not buried against another
        // labelled cell, and near enough for a 0.36-block badge to be legible. A big template (a
        // chunk frame locks thousands of cells) otherwise asks for six labels per cell every frame.
        java.util.List<Face> faces = new java.util.ArrayList<>();
        for (Map.Entry<BlockPos, Integer> e : snapshot.entrySet()) {
            BlockPos local = e.getKey();
            int lockId = e.getValue();
            if (lockId <= 0) continue;
            BlockPos world = origin.offset(local);
            if (cam.distanceToSqr(world.getX() + 0.5, world.getY() + 0.5, world.getZ() + 0.5) > MAX_DISTANCE_SQR) continue;
            String label = Integer.toString(lockId);
            for (Direction face : Direction.values()) {
                Integer neighbour = snapshot.get(local.relative(face));
                if (neighbour != null && neighbour > 0) continue;
                if (!facesCamera(cam, world, face)) continue;
                faces.add(new Face(world, face, label));
            }
        }
        if (faces.isEmpty()) return;

        // Two passes, one render type each: interleaving the backdrop with the digit's glyph type
        // flushes the batch on every switch, i.e. two draw calls per face.
        for (Face f : faces) {
            withFacePose(ps, cam, f.world(), f.face(), () ->
                drawQuad(ps, buffer, -BADGE_HALF, -BADGE_HALF, BADGE_HALF, BADGE_HALF, 0xC0202020));
        }
        buffer.endBatch(PANEL_QUAD);
        for (Face f : faces) {
            withFacePose(ps, cam, f.world(), f.face(), () -> drawDigit(ps, buffer, font, f.label()));
        }
        buffer.endBatch();
    }

    /** Past this, a badge is a few pixels and unreadable — not worth a draw. */
    private static final double MAX_DISTANCE_SQR = 32.0 * 32.0;

    private record Face(BlockPos world, Direction face, String label) {}

    /** True when {@code face} of the block at {@code world} is turned toward the camera. */
    private static boolean facesCamera(Vec3 cam, BlockPos world, Direction face) {
        double dx = cam.x - (world.getX() + 0.5 + face.getStepX() * FACE_INSET);
        double dy = cam.y - (world.getY() + 0.5 + face.getStepY() * FACE_INSET);
        double dz = cam.z - (world.getZ() + 0.5 + face.getStepZ() * FACE_INSET);
        return dx * face.getStepX() + dy * face.getStepY() + dz * face.getStepZ() > 0;
    }

    /** Run {@code draw} in the face's badge space: centred on the face, digit-up, at the UI scale. */
    private static void withFacePose(PoseStack ps, Vec3 cam, BlockPos world, Direction face, Runnable draw) {
        Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        // Top / bottom faces take a stable horizontal up (world +Z) so the digit doesn't flip as the
        // player turns.
        Vec3 up = face.getAxis() == Direction.Axis.Y ? new Vec3(0, 0, 1) : new Vec3(0, 1, 0);
        Vec3 right = up.cross(normal).normalize();

        ps.pushPose();
        ps.translate(world.getX() + 0.5 + face.getStepX() * FACE_INSET - cam.x,
            world.getY() + 0.5 + face.getStepY() * FACE_INSET - cam.y,
            world.getZ() + 0.5 + face.getStepZ() * FACE_INSET - cam.z);
        Matrix3f basis = new Matrix3f(
            (float) right.x, (float) right.y, (float) right.z,
            (float) up.x, (float) up.y, (float) up.z,
            (float) normal.x, (float) normal.y, (float) normal.z
        );
        ps.mulPose(new Quaternionf().setFromNormalized(basis));
        // Same world-space scale the X menu uses, so the badge shrinks with the rest of the editor UI.
        float worldScale = (float) ClientDisplayConfig.getWorldspaceScale();
        if (worldScale != 1.0f) ps.scale(worldScale, worldScale, worldScale);
        draw.run();
        ps.popPose();
    }

    private static void drawDigit(PoseStack ps, MultiBufferSource buffer, Font font, String label) {
        ps.pushPose();
        ps.translate(0, 0, 0.001f);
        ps.scale(TEXT_SCALE, -TEXT_SCALE, TEXT_SCALE);
        font.drawInBatch(label,
            -font.width(label) / 2f, -font.lineHeight / 2f,
            0xFFFFEEBB, false, ps.last().pose(), buffer,
            Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT);
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
        vc.addVertex(mat, (float) x1, (float) y1, (float) 0).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x2, (float) y1, (float) 0).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x2, (float) y2, (float) 0).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x1, (float) y2, (float) 0).setColor(r, g, b, a);
    }
}
