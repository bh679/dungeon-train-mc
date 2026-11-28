package games.brennan.dungeontrain.client.constellation;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.util.Map;
import java.util.UUID;

/**
 * The block under the crosshair keeps its outline when the patch hides it: drawn again over the
 * patch, light so it shows against the night. Vanilla's outline is drawn before the patch and would
 * be covered.
 */
final class ConstellationOutline {

    /** Light, as vanilla's is dark: the patch behind it is night. */
    private static final float R = 1f, G = 1f, B = 1f, A = 0.45f;

    private ConstellationOutline() {}

    /** After the patch, in the level's frame ({@code view}: the camera rotation; model-view identity). */
    static void draw(Matrix4f view, Vec3 cam, float partialTick, float patchAlpha) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.hideGui || !(mc.hitResult instanceof BlockHitResult hit)
            || hit.getType() != HitResult.Type.BLOCK) return;
        long target = hit.getBlockPos().asLong();
        ConstellationOcclusion.Hidden hidden = null;
        float level = 0f;
        for (Map.Entry<ConstellationOcclusion.Hidden, Float> e : ConstellationBlockFade.levels().entrySet()) {
            if (e.getKey().pos() == target) {
                hidden = e.getKey();
                level = e.getValue();
                break;
            }
        }
        if (hidden == null) return; // not hidden: vanilla's own outline shows
        BlockPos pos = hit.getBlockPos();
        Pose3dc pose = null;
        BlockGetter getter = mc.level;
        if (hidden.subLevel() != null) {
            ClientSubLevel sub = subLevel(mc, hidden.subLevel());
            if (sub == null || (pose = sub.renderPose(partialTick)) == null) return;
            getter = ConstellationCarpetSink.chunkAt(sub, pos);
            if (getter == null) return;
        }
        BlockState state = getter.getBlockState(pos);
        VoxelShape shape = state.getShape(getter, pos, mc.getCameraEntity() == null
            ? CollisionContext.empty() : CollisionContext.of(mc.getCameraEntity()));
        if (shape.isEmpty()) return;
        float a = A * Math.min(1f, level) * patchAlpha;
        if (a <= 0.02f) return;

        final Pose3dc p = pose;
        Vector3d local = new Vector3d(), w0 = new Vector3d(), w1 = new Vector3d();
        com.mojang.blaze3d.vertex.BufferBuilder bb = com.mojang.blaze3d.vertex.Tesselator.getInstance().begin(
            com.mojang.blaze3d.vertex.VertexFormat.Mode.LINES, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR_NORMAL);
        org.joml.Vector3f n = new org.joml.Vector3f();
        int[] count = {0};
        shape.forAllEdges((x0, y0, z0, x1, y1, z1) -> {
            place(local, pos.getX() + x0, pos.getY() + y0, pos.getZ() + z0, p, w0);
            place(local, pos.getX() + x1, pos.getY() + y1, pos.getZ() + z1, p, w1);
            // a hair toward the eye, so the lines win the depth test against the block's own faces
            float ax = (float) ((w0.x - cam.x) * PULL), ay = (float) ((w0.y - cam.y) * PULL), az = (float) ((w0.z - cam.z) * PULL);
            float bx = (float) ((w1.x - cam.x) * PULL), by = (float) ((w1.y - cam.y) * PULL), bz = (float) ((w1.z - cam.z) * PULL);
            view.transformDirection(n.set(bx - ax, by - ay, bz - az));
            if (n.lengthSquared() < 1.0e-12f) return;
            n.normalize();
            bb.addVertex(view, ax, ay, az).setColor(R, G, B, a).setNormal(n.x, n.y, n.z);
            bb.addVertex(view, bx, by, bz).setColor(R, G, B, a).setNormal(n.x, n.y, n.z);
            count[0]++;
        });
        com.mojang.blaze3d.vertex.MeshData mesh = bb.build();
        if (mesh == null) return;
        if (count[0] == 0) {
            mesh.close();
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getRendertypeLinesShader);
        RenderSystem.lineWidth(Math.max(2.5f, mc.getWindow().getWidth() / 1920f * 2.5f));
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(mesh);
        RenderSystem.lineWidth(1f);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** How far toward the eye the lines are drawn, as a fraction of their distance. */
    private static final double PULL = 0.995;

    private static void place(Vector3d local, double x, double y, double z, Pose3dc pose, Vector3d out) {
        local.set(x, y, z);
        if (pose != null) pose.transformPosition(local, out);
        else out.set(local);
    }

    private static ClientSubLevel subLevel(Minecraft mc, UUID id) {
        ClientSubLevelContainer container = SubLevelContainer.getContainer(mc.level);
        if (container == null) return null;
        for (ClientSubLevel sub : container.getAllSubLevels()) if (sub.getUniqueId().equals(id)) return sub;
        return null;
    }
}
