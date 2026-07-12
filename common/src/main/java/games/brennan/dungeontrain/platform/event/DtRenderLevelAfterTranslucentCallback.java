package games.brennan.dungeontrain.platform.event;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;

/**
 * Loader-neutral form of NeoForge's {@code RenderLevelStageEvent} filtered to the
 * {@code AFTER_TRANSLUCENT_BLOCKS} stage (client render thread). All 12 DT handlers
 * gate on exactly that stage, so the bridge owns the stage dispatch and this
 * callback fires only for it — mirroring Fabric's dedicated
 * {@code WorldRenderEvents.AFTER_TRANSLUCENT} callback (a Fabric bridge wires
 * straight to it, no stage check). Not cancellable; handlers draw world-space
 * overlays. All parameters are vanilla ({@link DeltaTracker} is the former
 * {@code event.getPartialTick()}).
 */
@FunctionalInterface
public interface DtRenderLevelAfterTranslucentCallback {

    void onRenderLevel(PoseStack poseStack, Camera camera, DeltaTracker deltaTracker);
}
