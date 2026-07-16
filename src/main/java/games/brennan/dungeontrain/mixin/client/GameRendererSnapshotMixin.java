package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.snapshot.RideSnapshotCapture;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the off-screen ride-snapshot pass at the TAIL of
 * {@code GameRenderer.render(DeltaTracker, boolean)} — after the live frame
 * (world + GUI) has already been drawn to the main target this frame, but
 * before it is blitted to the screen.
 *
 * <p>{@link RideSnapshotCapture#runOffscreenCapture} then renders the world one
 * extra time from the snapshot pose into a private off-screen target and reads
 * it back, so the player's on-screen view is never disturbed (no flicker). The
 * pose is applied by {@code CameraCinematicMixin} on {@code Camera.setup} during
 * that extra {@code renderLevel} pass while the capture is armed.</p>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererSnapshotMixin {

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("TAIL"))
    private void dungeontrain$offscreenRideSnapshot(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        if (renderLevel) {
            RideSnapshotCapture.runOffscreenCapture((GameRenderer) (Object) this, deltaTracker);
        }
    }
}
