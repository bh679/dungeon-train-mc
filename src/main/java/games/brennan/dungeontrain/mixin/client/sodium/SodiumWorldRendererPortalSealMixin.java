package games.brennan.dungeontrain.mixin.client.sodium;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.ClientPortalSeal;
import games.brennan.dungeontrain.client.portal.ClientPortalSwap;
import games.brennan.dungeontrain.client.portal.PortalArrivalTrace;
import games.brennan.dungeontrain.portal.PortalSealPlane;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Recompute the portal twin's seal cut from this frame's camera on <b>Sodium's</b> frame path — the
 * same thing {@code LevelRendererFrustumRefreshMixin} does at the head of vanilla's
 * {@code setupRender}, and the reason the arrival flash was fixed there and not here.
 *
 * <p>{@code ClientPortalSeal.beginFrame} runs from {@code ViewportEvent.ComputeCameraAngles}, and on
 * the frame a portal swap lands that event sees the camera before it has moved. The cut it leaves
 * behind is the on-train one — hide everything at or below bedrock — while the camera is already
 * below bedrock, so the first frame in the twin culls precisely the room the player is standing in.
 * Under Sodium that culling happens in {@code ViewportPortalSealMixin}, which reads the very same
 * stale cut, and Sodium's own {@code setupTerrain} is the last stop before it does: it is what
 * Sodium's merged {@code setupRender} calls, every frame, ahead of the occlusion walk.</p>
 *
 * <p>The handler takes no target arguments — Mixin allows that — so no Sodium type appears in DT's
 * signature and Sodium stays off the compile classpath, exactly as the sibling mixins in this
 * package do. The camera is the same object Sodium was handed, read through {@code Minecraft}.
 * Unconditional and every frame, like the vanilla hook: a config read and four comparisons. Iris's
 * shadow pass calls {@code setupTerrain} a second time per frame; the recompute is idempotent.</p>
 *
 * <p>Targeted by class name and applied only when Sodium is loaded ({@code SodiumMixinPlugin});
 * the config sets {@code defaultRequire: 0}, so a future Sodium rename of {@code setupTerrain}
 * costs the fix under Sodium rather than the client's launch. The trace line makes such a silent
 * drop visible in one run.</p>
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class SodiumWorldRendererPortalSealMixin {

    @Inject(method = "setupTerrain", at = @At("HEAD"), remap = false)
    private void dungeontrain$sealFromThisFramesCamera(CallbackInfo ci) {
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        PortalSealPlane.Cut before = ClientPortalSeal.cut();
        ClientPortalSeal.beginFrame(camera.x, camera.y);
        dungeontrain$trace(before, camera.y);
    }

    /** What the recompute changed on an arrival frame — the measurement, not the fix. */
    private static void dungeontrain$trace(PortalSealPlane.Cut before, double cameraY) {
        if (!PortalArrivalTrace.TRACE || !ClientPortalSwap.inArrivalWindow()) return;
        if (!PortalArrivalTrace.claimFrame()) return;
        PortalSealPlane.Cut after = ClientPortalSeal.cut();
        LogUtils.getLogger().info(
            "[DungeonTrain] Portal arrival frame (sodium): camY={} cut {}..{} -> {}..{} sealed={}",
            String.format("%.1f", cameraY), before.floorY(), before.roofY(),
            after.floorY(), after.roofY(), after.sealed());
    }
}
