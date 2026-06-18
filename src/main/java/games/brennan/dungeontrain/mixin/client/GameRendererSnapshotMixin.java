package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.snapshot.RideSnapshotCapture;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the live-frame ride-snapshot grab around the real
 * {@code GameRenderer.renderLevel(DeltaTracker)}:
 *
 * <ul>
 *   <li>HEAD → {@link RideSnapshotCapture#beginLiveCapture} arms the
 *       third-person override for this frame (so the world is drawn from the
 *       snapshot pose by the normal render path);</li>
 *   <li>TAIL → {@link RideSnapshotCapture#finishLiveCapture} reads the
 *       just-rendered world back (before the GUI) and restores the view.</li>
 * </ul>
 *
 * <p>The actual pose is applied by {@code CameraCinematicMixin} on
 * {@code Camera.setup} (called between these two points) while the capture is
 * armed.</p>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererSnapshotMixin {

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
    private void dungeontrain$beginRideSnapshot(DeltaTracker deltaTracker, CallbackInfo ci) {
        RideSnapshotCapture.beginLiveCapture((GameRenderer) (Object) this, deltaTracker);
    }

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("TAIL"))
    private void dungeontrain$finishRideSnapshot(DeltaTracker deltaTracker, CallbackInfo ci) {
        RideSnapshotCapture.finishLiveCapture((GameRenderer) (Object) this);
    }
}
