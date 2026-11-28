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
import games.brennan.dungeontrain.client.perks.PerksScreen;
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
 * A soft column of night sky rising off each constellation window: strongest at the carpet, gone
 * {@link #HEIGHT} blocks up. Hidden while you stand in one, where the sky patch takes over.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ConstellationWindowGlow {

    static final float HEIGHT = 1.5f;
    static final float BASE_ALPHA = 0.55f;

    private ConstellationWindowGlow() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!ConstellationBlocks.active() || ConstellationClient.inside()) return;
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
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferUploader.drawWithShader(mesh);
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /** The four sides of one window's column; {@code pose} places a carriage's window. */
    private static int column(BufferBuilder bb, Matrix4f view, Vec3 cam, long packed, Pose3dc pose) {
        int bx = BlockPos.getX(packed), by = BlockPos.getY(packed), bz = BlockPos.getZ(packed);
        float y0 = (float) ConstellationBlocks.WINDOW_HEIGHT, y1 = y0 + HEIGHT;
        float[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        int bottom = colour(BASE_ALPHA), top = colour(0f);
        Vector3d local = new Vector3d(), world = new Vector3d();
        for (int i = 0; i < 4; i++) {
            float[] a = corners[i], b = corners[(i + 1) % 4];
            put(bb, view, cam, pose, local, world, bx + a[0], by + y0, bz + a[1], bottom);
            put(bb, view, cam, pose, local, world, bx + b[0], by + y0, bz + b[1], bottom);
            put(bb, view, cam, pose, local, world, bx + b[0], by + y1, bz + b[1], top);
            put(bb, view, cam, pose, local, world, bx + a[0], by + y1, bz + a[1], top);
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

    /** The Perks menu's night at {@code alpha}. */
    static int colour(float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (a << 24) | (PerksScreen.SKY_BG & 0xFFFFFF);
    }

    private static Map<UUID, ClientSubLevel> subLevels(Minecraft mc) {
        ClientSubLevelContainer container = SubLevelContainer.getContainer(mc.level);
        Map<UUID, ClientSubLevel> out = new HashMap<>();
        if (container == null) return out;
        for (ClientSubLevel sub : container.getAllSubLevels()) out.put(sub.getUniqueId(), sub);
        return out;
    }
}
