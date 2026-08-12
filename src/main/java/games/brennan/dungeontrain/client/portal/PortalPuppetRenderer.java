package games.brennan.dungeontrain.client.portal;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.PortalPuppetsPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Draws the puppets — and is the only thing in the game that ever touches one.
 *
 * <p>A puppet is not in the level, so nothing draws it by default. This runs after the level's own
 * entities have been drawn and puts each puppet through the ordinary entity renderer, which is what
 * makes a player puppet look like a player and a zombie puppet look like a zombie, complete with
 * held items, armour, nameplate and shadow.</p>
 *
 * <p><b>Two spaces, resolved here.</b> A twin puppet's coordinates are already world coordinates. A
 * carriage puppet's are shipyard-local, and are transformed by that sub-level's <b>interpolated
 * render pose</b> — the same pose, at the same partial tick, that Sable draws the carriage's blocks
 * with. That is what nails a puppet to the carriage floor while the train moves: both are drawn from
 * one transform, so there is no cross-channel shimmer between them. The lookup mirrors
 * {@code client/CarriageGroupGapDebugRenderer}, which resolves its ghost cubes the same way.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PortalPuppetRenderer {

    private PortalPuppetRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        if (PortalPuppetsClient.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();

        // Sub-levels indexed once per frame: resolving each puppet directly would walk the level's
        // plot grid per puppet, which is the same reason the gap renderer builds this map.
        Map<UUID, ClientSubLevel> byUuid = null;

        Vector3d local = new Vector3d();
        Vector3d world = new Vector3d();

        for (PortalPuppetsClient.Puppet puppet : PortalPuppetsClient.all()) {
            PortalPuppetsPacket.Entry entry = puppet.entry();
            Entity model = puppet.model();

            double x = puppet.lerpX(partialTick);
            double y = puppet.lerpY(partialTick);
            double z = puppet.lerpZ(partialTick);

            if (entry.isPlotSpace()) {
                if (byUuid == null) byUuid = indexSubLevels(level);

                ClientSubLevel sub = byUuid.get(entry.subLevel());
                if (sub == null) continue;

                Pose3dc pose = sub.renderPose(partialTick);
                if (pose == null) continue;

                // The carriage may have been culled or not yet meshed. Skipping the frame is the
                // right answer: a puppet drawn against a stale pose would sit beside the corridor
                // rather than in it, which reads as a bug where a missing frame reads as nothing.
                local.set(x, y, z);
                pose.transformPosition(local, world);
                x = world.x;
                y = world.y;
                z = world.z;
            }

            // The model's own position drives lighting, the shadow and the nameplate's distance
            // fade, so it is set even though the draw below takes explicit coordinates.
            model.setPos(x, y, z);
            model.xOld = model.xo = x;
            model.yOld = model.yo = y;
            model.zOld = model.zo = z;

            int packedLight = dispatcher.getPackedLightCoords(model, partialTick);

            dispatcher.render(model,
                x - cam.x, y - cam.y, z - cam.z,
                puppet.lerpYaw(partialTick), partialTick,
                poseStack, buffer, packedLight);
        }

        // Flush what we queued. The level's own entity batch has already been drawn by this stage,
        // so nothing else is going to end it for us.
        buffer.endBatch();
    }

    private static Map<UUID, ClientSubLevel> indexSubLevels(ClientLevel level) {
        Map<UUID, ClientSubLevel> byUuid = new HashMap<>();
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return byUuid;

        for (ClientSubLevel sub : container.getAllSubLevels()) {
            byUuid.put(sub.getUniqueId(), sub);
        }
        return byUuid;
    }
}
