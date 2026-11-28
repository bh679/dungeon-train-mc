package games.brennan.dungeontrain.client.constellation;

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
import games.brennan.dungeontrain.block.ConstellationBlocks;
import games.brennan.dungeontrain.block.SkyboxSky;
import games.brennan.dungeontrain.client.skybox.SkyboxBlockIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A soft starlight glow rising off each cosmic carpet, a vertical gradient: strongest at the
 * carpet, gone {@link #HEIGHT} blocks up.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ConstellationWindowGlow {

    static final float HEIGHT = 0.5f;
    static final float BASE_ALPHA = 0.5f;

    private ConstellationWindowGlow() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!ConstellationBlocks.active()) return; // half a block tall: under the eye even standing in it
        SkyboxBlockIndex.Snapshot snapshot = SkyboxBlockIndex.snapshot();
        if (snapshot.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 cam = event.getCamera().getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Matrix4f view = event.getModelViewMatrix();

        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        int quads = 0;
        SkyboxBlockIndex.CubeSet main = snapshot.main().get(SkyboxSky.CONSTELLATION);
        if (main != null) for (long pos : main.positions()) quads += column(bb, view, cam, pos, null);
        if (!snapshot.subLevels().isEmpty()) {
            Map<UUID, ClientSubLevel> subs = subLevels(mc);
            for (SkyboxBlockIndex.SubLevelCubes entry : snapshot.subLevels()) {
                SkyboxBlockIndex.CubeSet cubes = entry.byVariant().get(SkyboxSky.CONSTELLATION);
                ClientSubLevel sub = cubes == null ? null : subs.get(entry.subLevelId());
                Pose3dc pose = sub == null ? null : sub.renderPose(partial);
                if (pose == null) continue;
                for (long pos : cubes.positions()) quads += column(bb, view, cam, pos, pose);
            }
        }
        MeshData mesh = bb.build();
        if (mesh == null) return;
        if (quads == 0) {
            mesh.close();
            return;
        }
        // The vertices already carry the camera rotation: draw in exactly the level's frame, with an
        // identity model-view (the stack may hold the rotation by now) and the level's projection.
        org.joml.Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(event.getProjectionMatrix(), com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);
        try {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
            RenderSystem.depthMask(false);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferUploader.drawWithShader(mesh);
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.restoreProjectionMatrix();
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /** The four sides of one window's column; {@code pose} places a carriage's window. */
    /** How far each side leans in by the top, in blocks: the glow stays over the carpet. */
    static final float LEAN = 0.3f;
    /** The glow's colour: a soft starlight violet, so it shows over the dark hole as well as the floor. */
    static final int GLOW_RGB = 0x9A8CFF;

    /**
     * One carpet's glow: its four edges rise half a block, leaning in, strongest at the carpet and gone
     * at the top. Leaning in, every side faces up, so from above all four show, over the carpet only.
     * {@code pose} places a carriage's carpet.
     */
    private static int column(BufferBuilder bb, Matrix4f view, Vec3 cam, long packed, Pose3dc pose) {
        int bx = BlockPos.getX(packed), by = BlockPos.getY(packed), bz = BlockPos.getZ(packed);
        float y0 = (float) ConstellationBlocks.WINDOW_HEIGHT, y1 = y0 + HEIGHT;
        float[][] base = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        float[][] top = {{LEAN, LEAN}, {1 - LEAN, LEAN}, {1 - LEAN, 1 - LEAN}, {LEAN, 1 - LEAN}};
        Vector3d local = new Vector3d(), world = new Vector3d();
        int strong = colour(BASE_ALPHA), clear = colour(0f);
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            put(bb, view, cam, pose, local, world, bx + base[i][0], by + y0, bz + base[i][1], strong);
            put(bb, view, cam, pose, local, world, bx + base[j][0], by + y0, bz + base[j][1], strong);
            put(bb, view, cam, pose, local, world, bx + top[j][0], by + y1, bz + top[j][1], clear);
            put(bb, view, cam, pose, local, world, bx + top[i][0], by + y1, bz + top[i][1], clear);
        }
        return 4;
    }

    private static void put(BufferBuilder bb, Matrix4f view, Vec3 cam, Pose3dc pose, Vector3d local, Vector3d world,
                            double x, double y, double z, int argb) {
        local.set(x, y, z);
        if (pose != null) pose.transformPosition(local, world);
        else world.set(local);
        bb.addVertex(view, (float) (world.x - cam.x), (float) (world.y - cam.y), (float) (world.z - cam.z)).setColor(argb);
    }

    /** The glow's colour at {@code alpha}. */
    static int colour(float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (a << 24) | GLOW_RGB;
    }

    private static Map<UUID, ClientSubLevel> subLevels(Minecraft mc) {
        ClientSubLevelContainer container = SubLevelContainer.getContainer(mc.level);
        Map<UUID, ClientSubLevel> out = new HashMap<>();
        if (container == null) return out;
        for (ClientSubLevel sub : container.getAllSubLevels()) out.put(sub.getUniqueId(), sub);
        return out;
    }
}
