package games.brennan.dungeontrain.client.skybox;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.GraphicsCapabilities;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns every {@code skybox_block} into a hole showing the sky, by writing its cube
 * faces into the depth buffer — with colour writes masked off — in the one-line gap
 * vanilla leaves between drawing the sky and drawing the world.
 *
 * <h2>Why this works</h2>
 * <p>Verified against the decompiled {@code LevelRenderer#renderLevel} for this
 * NeoForge build:</p>
 * <pre>
 *   957  RenderSystem.clear(...)                    colour AND depth cleared
 *   963  renderSky(...)                             draws sky, never writes depth
 *   964  dispatchRenderStage(AFTER_SKY, ...)   &lt;-- we run here
 *   972  renderSectionLayer(solid) ... cutout
 *   977  level.effects().constantAmbientLight() &lt;-- Sable draws sub-levels here
 *  1219  renderClouds / renderSnowAndRain
 * </pre>
 * <p>At our hook the depth buffer is empty and the sky is already painted. Writing
 * the cube's depth means terrain behind it fails the {@code LEQUAL} test and never
 * covers the sky, while anything in front is nearer and draws normally. Because
 * Sable's sub-level pass is also downstream of this hook, the punch works on
 * carriage geometry too — including one carriage occluding another.</p>
 *
 * <h2>Consequences worth knowing</h2>
 * <ul>
 *   <li>Clouds and rain draw <em>after</em> the punch and are depth-tested against
 *       it, so they are culled inside the hole. You see sun, moon, stars and the sky
 *       gradient, but no clouds and no weather. No ordering can fix this — the punch
 *       must precede terrain — so the hole reads as a void window rather than a pane
 *       of glass.</li>
 *   <li>The sky is drawn before terrain fog is configured, so the hole is unfogged:
 *       a distant skybox block stays crisp in fog.</li>
 *   <li>Underwater / blindness / lava make vanilla skip the sky entirely; the hole
 *       then shows the flat clear colour instead. Harmless.</li>
 * </ul>
 *
 * <h2>Shader packs</h2>
 * <p>Iris shades a deferred gbuffer. A colour-masked depth write leaves the albedo
 * and normal attachments untouched, so the composite pass would shade those pixels
 * from cleared gbuffer data — black, not sky. Rather than ship a known-wrong image
 * we disable the punch under a shader pack, leaving the block simply invisible.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class SkyboxPunchRenderer {

    private SkyboxPunchRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        Vec3 cam = event.getCamera().getPosition();
        // Feed the indexer even when the punch itself is disabled: it costs nothing
        // and means re-enabling mid-session has a warm index on the next sweep.
        SkyboxBlockIndex.reportCamera(cam);

        if (!ClientDisplayConfig.isSkyboxPunchEnabled()) return;
        if (GraphicsCapabilities.shaderPackActive()) return;

        SkyboxBlockIndex.Snapshot snapshot = SkyboxBlockIndex.snapshot();
        if (snapshot.isEmpty()) return;

        Matrix4f frustumMatrix = event.getModelViewMatrix();
        Frustum frustum = event.getFrustum();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);

        BufferBuilder builder = Tesselator.getInstance()
            .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);

        int quads = emitMainWorld(builder, frustumMatrix, frustum, cam, snapshot);
        quads += emitSubLevels(builder, frustumMatrix, frustum, cam, level, snapshot, partialTick);

        // Always build: it is what closes the Tesselator's building state. Returns
        // null when nothing was emitted, in which case there is nothing to draw and
        // nothing to close.
        MeshData mesh = builder.build();
        if (mesh == null || quads == 0) {
            if (mesh != null) mesh.close();
            return;
        }

        drawDepthOnly(mesh);
    }

    /** The actual punch: depth writes with colour writes masked off, then full restore. */
    private static void drawDepthOnly(MeshData mesh) {
        RenderSystem.setShader(GameRenderer::getPositionShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.colorMask(false, false, false, false);
        // Culling off makes the punch immune to winding under an arbitrary carriage
        // pose, and keeps it correct when the camera is inside the cube (front faces
        // clip against the near plane; back faces still write depth).
        RenderSystem.disableCull();

        BufferUploader.drawWithShader(mesh);

        // Restore exactly what the terrain pass on the next line expects.
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static int emitMainWorld(BufferBuilder builder, Matrix4f frustumMatrix, Frustum frustum,
                                     Vec3 cam, SkyboxBlockIndex.Snapshot snapshot) {
        long[] positions = snapshot.mainPositions();
        byte[] masks = snapshot.mainFaceMasks();
        double[] cx = new double[8];
        double[] cy = new double[8];
        double[] cz = new double[8];
        int quads = 0;

        for (int i = 0; i < positions.length; i++) {
            int mask = masks[i] & 0xFF;
            if (mask == 0) continue;
            int bx = BlockPos.getX(positions[i]);
            int by = BlockPos.getY(positions[i]);
            int bz = BlockPos.getZ(positions[i]);
            if (!frustum.isVisible(new AABB(bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0))) continue;

            for (int corner = 0; corner < 8; corner++) {
                cx[corner] = bx + SkyboxGeometry.cornerDx(corner);
                cy[corner] = by + SkyboxGeometry.cornerDy(corner);
                cz[corner] = bz + SkyboxGeometry.cornerDz(corner);
            }
            quads += SkyboxGeometry.emitCube(builder, frustumMatrix, cx, cy, cz, cam.x, cam.y, cam.z, mask);
        }
        return quads;
    }

    /**
     * Carriage cubes. Each corner goes through the sub-level's
     * {@link ClientSubLevel#renderPose(float) renderPose} — the very transform Sable
     * draws that carriage's blocks with this frame — so the hole stays welded to the
     * wall at speed instead of swimming against it. The pose is a rigid transform, so
     * transforming the eight corners keeps the faces planar and stays correct if a
     * carriage is ever allowed to rotate.
     */
    private static int emitSubLevels(BufferBuilder builder, Matrix4f frustumMatrix, Frustum frustum,
                                     Vec3 cam, ClientLevel level,
                                     SkyboxBlockIndex.Snapshot snapshot, float partialTick) {
        if (snapshot.subLevels().isEmpty()) return 0;
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return 0;

        Map<UUID, ClientSubLevel> byUuid = new HashMap<>();
        for (ClientSubLevel sub : container.getAllSubLevels()) {
            byUuid.put(sub.getUniqueId(), sub);
        }

        Vector3d local = new Vector3d();
        Vector3d world = new Vector3d();
        double[] cx = new double[8];
        double[] cy = new double[8];
        double[] cz = new double[8];
        int quads = 0;

        for (SkyboxBlockIndex.SubLevelCubes cubes : snapshot.subLevels()) {
            ClientSubLevel sub = byUuid.get(cubes.subLevelId());
            if (sub == null) continue;
            Pose3dc pose = sub.renderPose(partialTick);
            if (pose == null) continue;

            long[] positions = cubes.positions();
            byte[] masks = cubes.faceMasks();
            for (int i = 0; i < positions.length; i++) {
                int mask = masks[i] & 0xFF;
                if (mask == 0) continue;
                int bx = BlockPos.getX(positions[i]);
                int by = BlockPos.getY(positions[i]);
                int bz = BlockPos.getZ(positions[i]);

                double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
                double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
                for (int corner = 0; corner < 8; corner++) {
                    local.set(bx + SkyboxGeometry.cornerDx(corner),
                              by + SkyboxGeometry.cornerDy(corner),
                              bz + SkyboxGeometry.cornerDz(corner));
                    // Two-arg form: the single-arg overload mutates its input.
                    pose.transformPosition(local, world);
                    cx[corner] = world.x;
                    cy[corner] = world.y;
                    cz[corner] = world.z;
                    if (world.x < minX) minX = world.x;
                    if (world.x > maxX) maxX = world.x;
                    if (world.y < minY) minY = world.y;
                    if (world.y > maxY) maxY = world.y;
                    if (world.z < minZ) minZ = world.z;
                    if (world.z > maxZ) maxZ = world.z;
                }
                if (!frustum.isVisible(new AABB(minX, minY, minZ, maxX, maxY, maxZ))) continue;
                quads += SkyboxGeometry.emitCube(builder, frustumMatrix, cx, cy, cz, cam.x, cam.y, cam.z, mask);
            }
        }
        return quads;
    }
}
