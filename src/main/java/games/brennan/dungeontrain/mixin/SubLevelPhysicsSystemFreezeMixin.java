package games.brennan.dungeontrain.mixin;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import games.brennan.dungeontrain.ship.sable.PhysicsFreeze;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Issue #646 — gates the third and last per-body native reader (bytecode-verified against
 * {@code sable-2.0.2+mc1.21.1}): {@code SubLevelPhysicsSystem.updatePose(ServerSubLevel)} →
 * {@code pipeline.readPose} → native {@code Rapier3D.getPose}. Injecting at {@code updatePose}
 * (public, no internal {@code isRemoved()} guard) is a single choke point covering both callers —
 * the per-substep {@code updateAllPoses} loop and the post-{@code recoverSubLevel} re-read.
 *
 * <p>Skips the pose read for any sub-level DT has frozen (body removed from the scene but sub-level
 * still loaded), so the read never lands on a freed native handle. See
 * {@link ServerSubLevelFreezeMixin} for the other two readers and the freeze-flag storage, and
 * {@link PhysicsFreeze} for the pipeline op. {@code remap = false}: Sable's own names.</p>
 */
@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class SubLevelPhysicsSystemFreezeMixin {

    @Inject(method = "updatePose", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$skipFrozenUpdatePose(ServerSubLevel subLevel, CallbackInfo ci) {
        if (PhysicsFreeze.isFrozen(subLevel)) ci.cancel();
    }
}
