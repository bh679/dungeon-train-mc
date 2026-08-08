package games.brennan.dungeontrain.client.lod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3dc;

import java.util.Map;

/**
 * Draws the baked mesh for every carriage currently at the far LOD tier — one buffer bind and one
 * draw per layer per carriage, in place of Sable's per-section chunk render.
 *
 * <p>Anchoring follows the pattern proven by
 * {@link games.brennan.dungeontrain.client.CarriageGroupGapDebugRenderer}: take the sub-level's
 * {@link ClientSubLevel#renderPose(float)} interpolated to the current partial tick — the same pose
 * Sable renders the blocks with — and build the model matrix from it. Because it is literally the
 * same pose source, a baked carriage cannot drift against its real neighbours, which is what would
 * otherwise show up as a visible seam at the tier boundary.</p>
 *
 * <h2>Matrix</h2>
 *
 * <p>Reconstructs Sable's own transform rather than calling {@code SubLevelRenderData
 * .getTransformation}, so the partial tick is honoured: {@code translate(pose.position - camera)}
 * → {@code rotate(pose.orientation)} → {@code scale(pose.scale)} → {@code translate(bakeOrigin -
 * pose.rotationPoint)}. The final term is what lets a mesh baked against a fixed plot corner
 * survive a {@code rotationPoint} that moves — it is re-applied every frame instead of burned into
 * the vertices.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class CarriageMeshRenderer {

    private CarriageMeshRenderer() {}

    /**
     * Drawn after cutout-mipped blocks: late enough that the terrain depth buffer is populated (so
     * the meshes occlude correctly against the world), early enough to precede translucent passes.
     * All baked layers go out at this one stage — depth testing orders opaque geometry, so
     * splitting solid and cutout across their own stages would buy nothing.
     */
    private static final RenderLevelStageEvent.Stage STAGE =
        RenderLevelStageEvent.Stage.AFTER_CUTOUT_MIPPED_BLOCKS_BLOCKS;

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != STAGE) return;
        if (!CarriageLodController.ENABLED) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;

        Vec3 cam = event.getCamera().getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Matrix4f projection = event.getProjectionMatrix();
        Matrix4f viewMatrix = event.getPoseStack().last().pose();

        Matrix4f model = new Matrix4f();
        boolean drewAnything = false;

        for (ClientSubLevel sl : container.getAllSubLevels()) {
            if (!CarriageLod.isFar(sl)) continue;
            BakedCarriageMesh mesh = CarriageMeshCache.get(sl);
            if (mesh == null || mesh.isEmpty()) continue;

            buildModelMatrix(sl, mesh.bakeOrigin(), cam, partialTick, model);

            Matrix4f modelView = new Matrix4f(viewMatrix).mul(model);
            for (Map.Entry<RenderType, VertexBuffer> entry : mesh.buffers().entrySet()) {
                RenderType layer = entry.getKey();
                VertexBuffer vb = entry.getValue();
                if (vb.isInvalid()) continue;

                layer.setupRenderState();
                ShaderInstance shader = RenderSystem.getShader();
                if (shader != null) {
                    vb.bind();
                    vb.drawWithShader(modelView, projection, shader);
                }
                layer.clearRenderState();
                drewAnything = true;
            }
        }

        if (drewAnything) VertexBuffer.unbind();
    }

    /**
     * Rebuild {@code dest} as the model matrix for one far carriage. Mirrors Sable's
     * {@code SubLevelRenderData.getTransformation} + {@code getChunkOffset}, with the bake origin
     * folded into the final translation.
     */
    private static void buildModelMatrix(
        ClientSubLevel sl, BlockPos bakeOrigin, Vec3 cam, float partialTick, Matrix4f dest
    ) {
        Pose3dc pose = sl.renderPose(partialTick);
        Vector3dc position = pose.position();
        Vector3dc scale = pose.scale();
        Vector3dc rotationPoint = pose.rotationPoint();

        dest.identity();
        dest.translate(
            (float) (position.x() - cam.x),
            (float) (position.y() - cam.y),
            (float) (position.z() - cam.z));
        dest.rotate(new Quaternionf(pose.orientation()));
        dest.scale((float) scale.x(), (float) scale.y(), (float) scale.z());
        dest.translate(
            (float) (bakeOrigin.getX() - rotationPoint.x()),
            (float) (bakeOrigin.getY() - rotationPoint.y()),
            (float) (bakeOrigin.getZ() - rotationPoint.z()));
    }
}
