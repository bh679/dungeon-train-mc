package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.CarriageGroupGapPacket;
import games.brennan.dungeontrain.net.CarriageNextSpawnPacket;
import games.brennan.dungeontrain.net.CarriageSpawnCollisionPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * World-space debug overlay rendered between adjacent carriage groups in
 * the same train, anchored to the carriage's own physics frame so the
 * geometry rides the carriage smoothly between server snapshots.
 *
 * <p>Per-frame flow:
 * <ol>
 *   <li>Look up the {@link ClientSubLevel} for each entry's
 *       {@link CarriageGroupGapPacket.Entry#subLevelId()} via
 *       {@link SubLevelContainer#getContainer(ClientLevel)}.</li>
 *   <li>Take its {@link ClientSubLevel#renderPose(float)} interpolated to
 *       the current partial tick — this is the same pose Sable uses to
 *       render the sub-level's blocks, so our overlay tracks them
 *       perfectly without per-frame velocity guessing on our side.</li>
 *   <li>Transform every shipyard-space point (cube corners, label
 *       anchor) through the pose's {@code transformPosition} into world
 *       coordinates and draw there.</li>
 * </ol>
 *
 * <p>Cubes: one {@code 1×1×1} wireframe per integer block of gap, sitting
 * one block above the carriage roof on the centerline. Cube count =
 * {@code max(0, floor(distance))}, so a 3.0-block gap shows 3 cubes; a
 * 0.4-block gap shows 0; an overlap shows 0.</p>
 *
 * <p>Label: one billboard at the line midpoint, at
 * {@code y = roof + 2.5}, reading {@code "<distance> blocks"}. Scale 0.1
 * (4× nameplate) so it's legible from outside the train.</p>
 *
 * <p>Trains are locked to identity rotation by
 * {@link games.brennan.dungeontrain.train.TrainTransformProvider}, so the
 * pose's rotation is identity and our axis-aligned wireframe boxes stay
 * world-aligned after transform — no need to draw oriented bounding
 * boxes manually.</p>
 */
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class CarriageGroupGapDebugRenderer {

    private static final float CUBE_R = 0.85f;
    private static final float CUBE_G = 1.00f;
    private static final float CUBE_B = 0.30f;
    private static final float CUBE_A = 0.95f;
    /** Planned-next-spawn wireframe colour: bright orange/red, distinct from the cube + line palette. */
    private static final float PREVIEW_R = 1.00f;
    private static final float PREVIEW_G = 0.45f;
    private static final float PREVIEW_B = 0.10f;
    private static final float PREVIEW_A = 1.00f;
    /** Post-spawn collision-check wireframe colour when CLEAR — bright green. */
    private static final float COLLISION_CLEAR_R = 0.20f;
    private static final float COLLISION_CLEAR_G = 1.00f;
    private static final float COLLISION_CLEAR_B = 0.20f;
    private static final float COLLISION_CLEAR_A = 1.00f;
    /** Post-spawn collision-check wireframe colour when COLLIDING — bright red. */
    private static final float COLLISION_HIT_R = 1.00f;
    private static final float COLLISION_HIT_G = 0.10f;
    private static final float COLLISION_HIT_B = 0.10f;
    private static final float COLLISION_HIT_A = 1.00f;
    /** Cyan, contrasts with the yellow-green cubes so the precise float line is visually distinct. */
    private static final float LINE_R = 0.30f;
    private static final float LINE_G = 1.00f;
    private static final float LINE_B = 1.00f;
    private static final float LINE_A = 1.00f;
    /**
     * Half-thickness of the precise-length "line" — drawn as a very thin
     * wireframe box so it's visible against any backdrop (raw single-pixel
     * GL_LINES are nearly invisible at distance).
     */
    private static final double LINE_HALF_THICKNESS = 0.05;
    /** Larger than 0.1f because earlier passes were too small to read at typical
     * viewing distance; vanilla nameplate scale is 0.025 but they're meant to be
     * read at ~5 blocks. */
    private static final float TEXT_SCALE = 0.15f;
    /**
     * Vanilla entity-nameplate trick: first pass with low alpha (0x20) and a
     * background quad through SEE_THROUGH so the label is faintly visible
     * through walls; second pass full-bright opaque white via NORMAL so the
     * front-facing text is crisp and depth-tested. {@code Font.drawInBatch}
     * relies on this combination — a single NORMAL pass with a high-alpha
     * color is what we tried first and it didn't render reliably.
     */
    private static final int TEXT_FRONT_COLOR = 0xFFFFFFFF;
    private static final int TEXT_BACK_COLOR = 0x20FFFFFF;
    private static final int TEXT_BG = 0x40000000;

    private CarriageGroupGapDebugRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        // Each wireframe is gated on its own client-mirror flag — flips
        // take effect on the next frame regardless of broadcast timing.
        // Server-side gating in CarriageGroupGapTicker is a bandwidth
        // optimization; this client gate is the correctness mechanism.
        boolean cubesOn = DebugFlagsState.gapCubes();
        boolean lineOn = DebugFlagsState.gapLine();
        boolean nextSpawnOn = DebugFlagsState.nextSpawn();
        boolean collisionOn = DebugFlagsState.collision();
        List<CarriageGroupGapPacket.Entry> snapshot = (cubesOn || lineOn)
            ? CarriageGroupGapState.snapshot() : List.of();
        List<CarriageNextSpawnPacket.Entry> previews = nextSpawnOn
            ? CarriageNextSpawnState.snapshot() : List.of();
        List<CarriageSpawnCollisionPacket.Entry> collisions = collisionOn
            ? CarriageSpawnCollisionState.snapshot() : List.of();
        if (snapshot.isEmpty() && previews.isEmpty() && collisions.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel clientLevel = mc.level;
        if (clientLevel == null) return;
        ClientSubLevelContainer container = SubLevelContainer.getContainer(clientLevel);
        if (container == null) return;

        // Index sub-levels by UUID once per frame so each entry's lookup
        // is O(1) — getAllSubLevels() walks the level's plot grid.
        Map<UUID, ClientSubLevel> byUuid = new HashMap<>();
        for (ClientSubLevel sl : container.getAllSubLevels()) {
            byUuid.put(sl.getUniqueId(), sl);
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        Font font = mc.font;
        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        // Cubes pass — single PoseStack push for the whole batch.
        VertexConsumer linesVc = buffer.getBuffer(RenderType.lines());
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        Vector3d local = new Vector3d();
        Vector3d world = new Vector3d();
        for (CarriageGroupGapPacket.Entry entry : snapshot) {
            ClientSubLevel sub = byUuid.get(entry.subLevelId());
            if (sub == null) continue;
            Pose3dc pose = sub.renderPose(partialTick);
            if (pose == null) continue;

            if (cubesOn) {
                int firstCubeX = (int) Math.floor(0.0); // first integer offset along +X
                int afterLastCubeX = (int) Math.floor(entry.distance());
                // One cube per integer offset in [0, floor(distance)). Negative
                // / zero gap → loop body never runs, no cubes drawn.
                for (int xi = firstCubeX; xi < afterLastCubeX; xi++) {
                    local.set(entry.localStartX() + xi, entry.localY(), entry.localZ() - 0.5);
                    pose.transformPosition(local, world);
                    double wx0 = world.x;
                    double wy0 = world.y;
                    double wz0 = world.z;
                    local.set(entry.localStartX() + xi + 1, entry.localY() + 1, entry.localZ() + 0.5);
                    pose.transformPosition(local, world);
                    double wx1 = world.x;
                    double wy1 = world.y;
                    double wz1 = world.z;
                    LevelRenderer.renderLineBox(ps, linesVc,
                        new AABB(
                            Math.min(wx0, wx1), Math.min(wy0, wy1), Math.min(wz0, wz1),
                            Math.max(wx0, wx1), Math.max(wy0, wy1), Math.max(wz0, wz1)),
                        CUBE_R, CUBE_G, CUBE_B, CUBE_A);
                }
            }

            if (lineOn) {
                // Precise-length line — one block above the cube row, exactly
                // {@code distance} blocks long along +X. Drawn as a thin
                // wireframe box (half-thickness LINE_HALF_THICKNESS in Y/Z) so
                // it reads clearly even at distance, and so overlapping
                // (negative-distance) seams still show a visible bar going
                // backward into THIS group.
                double lineY = entry.localY() + 1.0;
                local.set(entry.localStartX(), lineY - LINE_HALF_THICKNESS, entry.localZ() - LINE_HALF_THICKNESS);
                pose.transformPosition(local, world);
                double lx0 = world.x, ly0 = world.y, lz0 = world.z;
                local.set(entry.localStartX() + entry.distance(), lineY + LINE_HALF_THICKNESS, entry.localZ() + LINE_HALF_THICKNESS);
                pose.transformPosition(local, world);
                double lx1 = world.x, ly1 = world.y, lz1 = world.z;
                LevelRenderer.renderLineBox(ps, linesVc,
                    new AABB(
                        Math.min(lx0, lx1), Math.min(ly0, ly1), Math.min(lz0, lz1),
                        Math.max(lx0, lx1), Math.max(ly0, ly1), Math.max(lz0, lz1)),
                    LINE_R, LINE_G, LINE_B, LINE_A);
            }
        }

        // Planned-next-spawn wireframe — orange box showing where the
        // appender will place the next group when J is pressed. The world
        // origin in the packet was computed from the reference (lead/tail)
        // ship's position at server-tick time; we re-anchor it to that
        // reference's interpolated render-pose every frame so the
        // wireframe rides smoothly with the train and stays in lockstep
        // with the actual placement when the spawn fires.
        for (CarriageNextSpawnPacket.Entry preview : previews) {
            ClientSubLevel referenceSub = byUuid.get(preview.referenceShipId());
            if (referenceSub == null) continue;
            Pose3dc refPose = referenceSub.renderPose(partialTick);
            if (refPose == null) continue;

            // Server's worldOrigin was computed from the reference ship's
            // shipToWorld(refShipyardOrigin) at broadcast time. Convert
            // back to that reference's shipyard space using its current
            // pose, so any drift between broadcast tick and now is folded
            // out — then re-apply the live pose to project to world.
            local.set(preview.worldOriginX(), preview.worldOriginY(), preview.worldOriginZ());
            refPose.transformPositionInverse(local);
            double localOriginX = local.x;
            double localOriginY = local.y;
            double localOriginZ = local.z;

            local.set(localOriginX, localOriginY, localOriginZ);
            refPose.transformPosition(local, world);
            double cornerMinX = world.x;
            double cornerMinY = world.y;
            double cornerMinZ = world.z;

            local.set(
                localOriginX + preview.sizeX(),
                localOriginY + preview.sizeY(),
                localOriginZ + preview.sizeZ());
            refPose.transformPosition(local, world);
            double cornerMaxX = world.x;
            double cornerMaxY = world.y;
            double cornerMaxZ = world.z;

            LevelRenderer.renderLineBox(ps, linesVc,
                new AABB(
                    Math.min(cornerMinX, cornerMaxX), Math.min(cornerMinY, cornerMaxY), Math.min(cornerMinZ, cornerMaxZ),
                    Math.max(cornerMinX, cornerMaxX), Math.max(cornerMinY, cornerMaxY), Math.max(cornerMinZ, cornerMaxZ)),
                PREVIEW_R, PREVIEW_G, PREVIEW_B, PREVIEW_A);
        }

        // Post-spawn collision-check wireframe — green if the 1×3×5 box at
        // the new carriage's first block (ship-space) is clear of all
        // other carriages, red if it overlaps. Gated on the per-flag
        // {@code collision} toggle (off by default); the {@code collisions}
        // list is empty when the flag is off, so this loop short-circuits
        // without rendering. Origin is in shipyard coords so
        // {@code transformPosition} via the new ship's interpolated
        // render-pose lands the box exactly on the carriage, riding with
        // it perfectly between server snapshots.
        for (CarriageSpawnCollisionPacket.Entry collision : collisions) {
            ClientSubLevel newSub = byUuid.get(collision.newShipId());
            if (newSub == null) continue;
            Pose3dc newPose = newSub.renderPose(partialTick);
            if (newPose == null) continue;

            local.set(
                collision.shipyardOriginX(),
                collision.shipyardOriginY(),
                collision.shipyardOriginZ());
            newPose.transformPosition(local, world);
            double boxMinX = world.x;
            double boxMinY = world.y;
            double boxMinZ = world.z;

            local.set(
                collision.shipyardOriginX() + collision.sizeX(),
                collision.shipyardOriginY() + collision.sizeY(),
                collision.shipyardOriginZ() + collision.sizeZ());
            newPose.transformPosition(local, world);
            double boxMaxX = world.x;
            double boxMaxY = world.y;
            double boxMaxZ = world.z;

            float r = collision.colliding() ? COLLISION_HIT_R : COLLISION_CLEAR_R;
            float g = collision.colliding() ? COLLISION_HIT_G : COLLISION_CLEAR_G;
            float b = collision.colliding() ? COLLISION_HIT_B : COLLISION_CLEAR_B;
            float a = collision.colliding() ? COLLISION_HIT_A : COLLISION_CLEAR_A;
            LevelRenderer.renderLineBox(ps, linesVc,
                new AABB(
                    Math.min(boxMinX, boxMaxX), Math.min(boxMinY, boxMaxY), Math.min(boxMinZ, boxMaxZ),
                    Math.max(boxMinX, boxMaxX), Math.max(boxMinY, boxMaxY), Math.max(boxMinZ, boxMaxZ)),
                r, g, b, a);
        }

        ps.popPose();
        buffer.endBatch(RenderType.lines());

        // Label pass — one PoseStack push per label since each one billboards.
        // The "X.XX blocks" billboard label belongs to the precise-length
        // line conceptually (same data, same colour family); gate it on
        // {@code lineOn} so the label only shows when the line is enabled.
        if (lineOn) {
        for (CarriageGroupGapPacket.Entry entry : snapshot) {
            ClientSubLevel sub = byUuid.get(entry.subLevelId());
            if (sub == null) continue;
            Pose3dc pose = sub.renderPose(partialTick);
            if (pose == null) continue;

            // Label sits ~0.7 blocks above the precise line (line is at
            // localY + 1.0, label at localY + 1.7), centered along the line.
            local.set(
                entry.localStartX() + entry.distance() * 0.5,
                entry.localY() + 1.7,
                entry.localZ());
            pose.transformPosition(local, world);

            String text = String.format(Locale.ROOT, "%.2f blocks", entry.distance());
            ps.pushPose();
            ps.translate(world.x - cam.x, world.y - cam.y, world.z - cam.z);
            ps.mulPose(camera.rotation());
            ps.scale(-TEXT_SCALE, -TEXT_SCALE, TEXT_SCALE);
            int textWidth = font.width(text);
            Matrix4f mat = ps.last().pose();
            // Pass 1: SEE_THROUGH with background quad — visible through walls,
            // dim. Provides the dark backdrop the front-facing text reads
            // against and keeps the label discoverable from any angle.
            font.drawInBatch(text,
                -textWidth / 2f, 0,
                TEXT_BACK_COLOR, false, mat, buffer,
                Font.DisplayMode.SEE_THROUGH, TEXT_BG, LightTexture.FULL_BRIGHT);
            // Pass 2: NORMAL, opaque white, no background — crisp front-facing
            // text on top of the SEE_THROUGH quad. Same two-pass strategy
            // vanilla EntityRenderer.renderNameTag uses for entity name tags.
            font.drawInBatch(text,
                -textWidth / 2f, 0,
                TEXT_FRONT_COLOR, false, mat, buffer,
                Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
            ps.popPose();
        }
        }
        buffer.endBatch();
    }
}
