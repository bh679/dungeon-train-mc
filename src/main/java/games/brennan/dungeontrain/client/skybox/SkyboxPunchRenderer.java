package games.brennan.dungeontrain.client.skybox;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
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
import games.brennan.dungeontrain.block.SkyboxSky;
import games.brennan.dungeontrain.client.ShaderCompat;
import games.brennan.dungeontrain.client.ShaderDiagnostics;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Camera;
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
import org.slf4j.Logger;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns every skybox block into a hole showing its own sky.
 *
 * <h2>Why this hook</h2>
 * <p>Verified against the decompiled {@code LevelRenderer#renderLevel} for this NeoForge
 * build:</p>
 * <pre>
 *   957  RenderSystem.clear(...)                    colour AND depth cleared (never stencil)
 *   961  setupFog(FOG_SKY)
 *   962  setShader(positionShader)
 *   963  renderSky(...)                             draws sky, never writes depth
 *   964  dispatchRenderStage(AFTER_SKY, ...)   &lt;-- both our passes run here
 *   966  setupFog(FOG_TERRAIN)                      re-arms fog right after us
 *   972  renderSectionLayer(solid) ... cutout
 *   977  level.effects().constantAmbientLight() &lt;-- Sable draws sub-levels here
 *  1219  renderClouds / renderSnowAndRain
 * </pre>
 * <p>At our hook the depth buffer is empty and the sky is already painted. Writing each
 * cube's depth means terrain behind it fails the {@code LEQUAL} test and never covers the
 * sky, while anything in front is nearer and draws normally. Because Sable's sub-level pass
 * is also downstream of this hook, it works on carriage geometry too — including one carriage
 * occluding another.</p>
 *
 * <h2>Two passes</h2>
 * <p>The mask pass punches depth and stamps each cube with its variant's stencil id. The sky
 * pass then draws each variant's sky restricted to its own id — see {@link SkyboxStencil}.
 * Without a stencil buffer only the mask pass runs, which reveals the live sky: right for
 * {@link SkyboxSky#SURFACE} above ground, wrong but coherent for the rest.</p>
 *
 * <h2>Consequences worth knowing</h2>
 * <ul>
 *   <li>Clouds and rain draw <em>after</em> the punch and are depth-tested against it, so they
 *       are culled inside the hole. No ordering can fix this — the punch must precede terrain —
 *       so a hole reads as a void window rather than a pane of glass.</li>
 *   <li>The hole is unfogged, so a distant skybox block stays crisp in fog.</li>
 * </ul>
 *
 * <h2>Shader packs</h2>
 * <p>Iris shades a deferred gbuffer. A colour-masked depth write leaves the albedo and normal
 * attachments untouched, so the composite pass would shade those pixels from cleared gbuffer
 * data — black, not sky. Under a pack the punch therefore runs stencil-free (no per-variant
 * sky pass; the pack owns the sky) and a second pass at {@code AFTER_WEATHER},
 * {@link SkyboxHoleReopen}, pushes every hole pixel that is still visible back to the far plane
 * so the composite paints the pack's own sky there. The on/off verdict lives in
 * {@link ShaderCompat#allows} alongside the other atmosphere systems'.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class SkyboxPunchRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Last reported set of on-screen variants, so the diagnostic below logs on change rather
     * than every frame. Purely observational; never read by the render path.
     */
    private static String lastPaintedReport = "";

    private SkyboxPunchRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        boolean afterSky = stage == RenderLevelStageEvent.Stage.AFTER_SKY;
        boolean afterWeather = stage == RenderLevelStageEvent.Stage.AFTER_WEATHER;
        if (!afterSky && !afterWeather) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        boolean shaders = ShaderCompat.active();

        if (afterSky) {
            // Feed the indexer even when the effect is disabled: it costs nothing and means
            // re-enabling mid-session has a warm index on the next sweep.
            SkyboxBlockIndex.reportCamera(cam);
            if (ShaderDiagnostics.recording()) {
                ShaderDiagnostics.recordLevelFboStencil(SkyboxStencil.boundFramebufferStencil());
            }
        } else if (!shaders) {
            // The reopen pass exists only for a deferred pack. Vanilla's sky pixels under the
            // punch are already the sky, and its per-variant skies were drawn at AFTER_SKY.
            return;
        }

        if (!ClientDisplayConfig.isSkyboxPunchEnabled()) return;

        // Read before the shader gate — it is a volatile field read, and taking it here is what
        // lets the diagnostics panel tell "no skybox blocks near the camera" apart from "blocks
        // are right here and the pack has the effect switched off".
        SkyboxBlockIndex.Snapshot snapshot = SkyboxBlockIndex.snapshot();
        if (afterSky && ShaderDiagnostics.recording()) {
            ShaderDiagnostics.recordSkybox(countCubes(snapshot), describeVariants(snapshot),
                SkyboxStencil.isAvailable(), false);
        }

        if (!ShaderCompat.allows(ShaderCompat.Feature.SKYBOX_BLOCKS)) return;
        if (snapshot.isEmpty()) return;

        Matrix4f frustumMatrix = event.getModelViewMatrix();
        Frustum frustum = event.getFrustum();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Map<UUID, ClientSubLevel> subLevels = indexSubLevels(level, snapshot);

        if (afterWeather) {
            // Under a pack: mark the hole pixels nothing has covered since the punch, and push
            // them back to the far plane so the composite treats them as sky. Same meshes, same
            // matrices as the punch — see SkyboxHoleReopen for why this is a stencil pass.
            SkyboxHoleReopen.run(() -> {
                for (SkyboxSky sky : SkyboxSky.values()) {
                    MeshData mesh = buildMesh(sky, snapshot, subLevels, frustumMatrix, frustum, cam, partialTick);
                    if (mesh != null) BufferUploader.drawWithShader(mesh);
                }
            });
            return;
        }

        EnumSet<SkyboxSky> painted = EnumSet.noneOf(SkyboxSky.class);

        // Only variants that draw their own sky need the mask. A view containing nothing but
        // LIVE blocks does no stencil work at all — that variant is satisfied by the punch.
        // Under a pack no variant draws its own sky (the pack's composite owns the sky), so the
        // punch runs stencil-free and the reopen pass above does the rest.
        boolean wantsStencil = !shaders && (snapshot.main().keySet().stream().anyMatch(SkyboxSky::hasOwnSky)
            || snapshot.subLevels().stream().anyMatch(e -> e.byVariant().keySet().stream().anyMatch(SkyboxSky::hasOwnSky)));
        boolean stencil = wantsStencil && SkyboxStencil.isAvailable();

        try {
            if (stencil) SkyboxStencil.beginMaskPass();
            beginMaskState();

            // Build and draw one variant at a time. Tesselator.getInstance() is a single
            // shared buffer, so holding several MeshData at once would let them overwrite
            // each other — each mesh is consumed before the next is begun.
            for (SkyboxSky sky : SkyboxSky.values()) {
                MeshData mesh = buildMesh(sky, snapshot, subLevels, frustumMatrix, frustum, cam, partialTick);
                if (mesh == null) continue;
                if (stencil) SkyboxStencil.maskRef(sky.stencilRef());
                BufferUploader.drawWithShader(mesh); // closes the mesh
                painted.add(sky);
            }
            endMaskState();

            reportPainted(painted, stencil);
            if (ShaderDiagnostics.recording()) {
                ShaderDiagnostics.recordSkybox(countCubes(snapshot), describeVariants(snapshot),
                    stencil, !painted.isEmpty());
            }

            if (stencil) {
                SkyboxStencil.beginSkyPass();
                for (SkyboxSky sky : painted) {
                    if (!sky.hasOwnSky()) continue; // LIVE: the punch already revealed the real sky
                    SkyboxStencil.skyRef(sky.stencilRef());
                    SkyboxStencil.drawSky(sky, frustumMatrix, event.getProjectionMatrix(), camera, partialTick);
                }
            }
        } finally {
            if (stencil) SkyboxStencil.endStencil();
            // The sky sources set their own blend/shader state; hand the terrain pass on the
            // next line exactly what it expects.
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.enableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /** Total indexed skybox cubes, main world and carriages — the panel's "is anything here?". */
    private static int countCubes(SkyboxBlockIndex.Snapshot snapshot) {
        int n = 0;
        for (SkyboxBlockIndex.CubeSet cubes : snapshot.main().values()) {
            n += cubes.positions().length;
        }
        for (SkyboxBlockIndex.SubLevelCubes entry : snapshot.subLevels()) {
            for (SkyboxBlockIndex.CubeSet cubes : entry.byVariant().values()) {
                n += cubes.positions().length;
            }
        }
        return n;
    }

    /** The indexed variants, comma-joined, for one line of the diagnostics panel. */
    private static String describeVariants(SkyboxBlockIndex.Snapshot snapshot) {
        EnumSet<SkyboxSky> seen = EnumSet.noneOf(SkyboxSky.class);
        seen.addAll(snapshot.main().keySet());
        for (SkyboxBlockIndex.SubLevelCubes entry : snapshot.subLevels()) {
            seen.addAll(entry.byVariant().keySet());
        }
        StringBuilder sb = new StringBuilder();
        for (SkyboxSky sky : seen) {
            if (sb.length() > 0) sb.append(',');
            sb.append(sky.name());
        }
        return sb.toString();
    }

    /**
     * Log which variants are on screen, and with which stencil refs, whenever that set
     * changes.
     *
     * <p>Diagnostic rather than decorative: a variant showing the wrong sky and a variant
     * whose blocks were never indexed look identical through the hole, and this is what tells
     * those two apart. Logs on change only, so it is quiet in steady state.</p>
     */
    private static void reportPainted(EnumSet<SkyboxSky> painted, boolean stencil) {
        StringBuilder sb = new StringBuilder();
        for (SkyboxSky sky : painted) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(sky.name()).append("=ref").append(sky.stencilRef());
            if (!sky.hasOwnSky()) sb.append("(reveal-only)");
        }
        String report = sb + (stencil ? " [stencil on]" : " [stencil OFF - all reveal live sky]");
        if (report.equals(lastPaintedReport)) return;
        lastPaintedReport = report;
        LOGGER.info("[DungeonTrain] Skybox variants on screen: {}", report);
    }

    /** Depth-only writes: the mask must shape the depth buffer without touching colour. */
    private static void beginMaskState() {
        RenderSystem.setShader(GameRenderer::getPositionShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.colorMask(false, false, false, false);
        // Culling off makes the mask immune to winding under an arbitrary carriage pose, and
        // keeps it correct when the camera is inside a cube (front faces clip against the near
        // plane; back faces still write depth).
        RenderSystem.disableCull();
    }

    private static void endMaskState() {
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.enableCull();
    }

    /**
     * The mesh for one variant's visible cubes, or {@code null} if it has none on screen.
     * Ownership passes to the caller — {@code BufferUploader.drawWithShader} closes it.
     */
    private static MeshData buildMesh(SkyboxSky sky, SkyboxBlockIndex.Snapshot snapshot,
                                      Map<UUID, ClientSubLevel> subLevels, Matrix4f frustumMatrix,
                                      Frustum frustum, Vec3 cam, float partialTick) {
        BufferBuilder builder = Tesselator.getInstance()
            .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);

        int quads = emitMainWorld(builder, frustumMatrix, frustum, cam, snapshot.main().get(sky));
        quads += emitSubLevels(builder, frustumMatrix, frustum, cam, snapshot, subLevels, sky, partialTick);

        // Always build: it is what closes the Tesselator's building state. Returns null when
        // nothing was emitted, in which case there is nothing to draw or to close.
        MeshData mesh = builder.build();
        if (mesh == null) return null;
        if (quads == 0) {
            mesh.close();
            return null;
        }
        return mesh;
    }

    private static Map<UUID, ClientSubLevel> indexSubLevels(ClientLevel level, SkyboxBlockIndex.Snapshot snapshot) {
        if (snapshot.subLevels().isEmpty()) return Map.of();
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return Map.of();
        Map<UUID, ClientSubLevel> byUuid = new HashMap<>();
        for (ClientSubLevel sub : container.getAllSubLevels()) {
            byUuid.put(sub.getUniqueId(), sub);
        }
        return byUuid;
    }

    private static int emitMainWorld(BufferBuilder builder, Matrix4f frustumMatrix, Frustum frustum,
                                     Vec3 cam, SkyboxBlockIndex.CubeSet cubes) {
        if (cubes == null) return 0;
        long[] positions = cubes.positions();
        byte[] masks = cubes.faceMasks();
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
     * {@link ClientSubLevel#renderPose(float) renderPose} — the very transform Sable draws
     * that carriage's blocks with this frame — so the hole stays welded to the wall at speed
     * instead of swimming against it. The pose is a rigid transform, so transforming the eight
     * corners keeps the faces planar and stays correct if a carriage is ever allowed to rotate.
     */
    private static int emitSubLevels(BufferBuilder builder, Matrix4f frustumMatrix, Frustum frustum,
                                     Vec3 cam, SkyboxBlockIndex.Snapshot snapshot,
                                     Map<UUID, ClientSubLevel> subLevels, SkyboxSky sky, float partialTick) {
        if (subLevels.isEmpty()) return 0;

        Vector3d local = new Vector3d();
        Vector3d world = new Vector3d();
        double[] cx = new double[8];
        double[] cy = new double[8];
        double[] cz = new double[8];
        int quads = 0;

        for (SkyboxBlockIndex.SubLevelCubes entry : snapshot.subLevels()) {
            SkyboxBlockIndex.CubeSet cubes = entry.byVariant().get(sky);
            if (cubes == null) continue;
            ClientSubLevel sub = subLevels.get(entry.subLevelId());
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
