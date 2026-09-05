package games.brennan.dungeontrain.mixin;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import games.brennan.dungeontrain.ship.sable.TrainRotationLock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carriage rotation lock, pose side. {@code SubLevelPhysicsSystem.updatePose(ServerSubLevel)} is
 * where Sable reads the integrated native pose back and writes it into the sub-level's
 * {@code logicalPose()} — and it runs once per <em>substep</em> (from {@code updateAllPoses}), not
 * once per server tick. DT's own kinematic correction in
 * {@code SableManagedShip.applyTickOutput} only lands on {@code LevelTickEvent.Post}, so between
 * corrections a carriage whose centre of mass a player's build has shifted tilts under gravity
 * torque, and every consumer of the pose (collision, tracking, networking, the renderer) sees the
 * tilt. Clamping here closes that window: a DT carriage's orientation is identity at every substep.
 *
 * <p>Injecting at {@code RETURN} rather than {@code TAIL} covers the method's early return (the
 * NaN-pose {@code recoverSubLevel} branch) as well as its normal exit. Two writes, no allocation:
 * {@code Pose3d.orientation()} hands back the live mutable {@code Quaterniond}, and
 * {@code latestAngularVelocity} — which Sable derives just above from the pose difference and
 * networks to clients — is zeroed so no client interpolates a spin the server does not have.</p>
 *
 * <p>This is deliberately a separate class from {@link SubLevelPhysicsSystemFreezeMixin}: that one
 * cancels the same method at {@code HEAD} for a soft-frozen body (a parked carriage does no pose
 * read at all), this one clamps the result for a locked one. A frozen carriage is parked at an
 * already-identity pose, so the cancel taking precedence is correct.</p>
 *
 * <p>{@code remap = false}: Sable's own names. <b>Re-audit on any {@code sable_version} bump</b>,
 * alongside the freeze mixins.</p>
 */
@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class SubLevelPhysicsSystemRotationLockMixin {

    @Inject(method = "updatePose", at = @At("RETURN"))
    private void dungeonTrain$flattenLockedPose(ServerSubLevel subLevel, CallbackInfo ci) {
        if (!TrainRotationLock.isLocked(subLevel)) return;
        subLevel.logicalPose().orientation().identity();
        subLevel.latestAngularVelocity.set(0.0, 0.0, 0.0);
    }
}
