package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import games.brennan.dungeontrain.client.snapshot.RideSnapshotCapture;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the no-hijack ride-snapshot grab at the HEAD of
 * {@code GameRenderer.renderLevel(DeltaTracker)}.
 *
 * <p>When a shot is pending, {@link RideSnapshotCapture#beginNestedCapture}
 * renders one extra full pass from the snapshot pose (into the main target),
 * reads it back, then restores the camera — all before the original pass runs.
 * The original {@code renderLevel} invocation then draws the player's real view
 * and overwrites the snapshot pose, so nothing but the real view reaches the
 * screen (no flicker). The nested pass is re-entrant-safe: its own HEAD hook
 * short-circuits on {@code RideSnapshotCapture.isCapturing()}.</p>
 *
 * <p>The pose is applied by {@code CameraCinematicMixin} on {@code Camera.setup}
 * during the nested pass while the capture is armed.</p>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererSnapshotMixin {

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
    private void dungeontrain$rideSnapshot(DeltaTracker deltaTracker, CallbackInfo ci) {
        RideSnapshotCapture.beginNestedCapture((GameRenderer) (Object) this, deltaTracker);
    }

    /**
     * The snapshot pass renders at the player's own FOV setting, whatever is zooming the real view.
     *
     * <p>{@code getFov} is where every zoom lands — the spyglass, sprint and speed effects, and
     * other mods' handlers (a camera mod's viewfinder overrides it outright). They are all about
     * what the player is looking through; the snapshot is a third-person photo <em>of</em> the
     * player and has no business inheriting them. Taken on the value {@code renderLevel} receives
     * rather than inside {@code getFov}, so it holds whatever anybody injects in there.</p>
     */
    @ModifyExpressionValue(
        method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;getFov(Lnet/minecraft/client/Camera;FZ)D"
        )
    )
    private double dungeontrain$snapshotAtOwnFov(double fov) {
        if (!RideSnapshotCapture.isCapturing()) return fov;
        return Minecraft.getInstance().options.fov().get();
    }
}
