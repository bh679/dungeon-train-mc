package games.brennan.dungeontrain.mixin;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import games.brennan.dungeontrain.ship.sable.DtKinematicPoseHolder;
import games.brennan.dungeontrain.ship.sable.KinematicPoseTarget;
import games.brennan.dungeontrain.ship.sable.TrainRotationLock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Carriage pose in double precision. {@code SubLevelPhysicsSystem.updatePose(ServerSubLevel)} copies
 * Rapier's single-precision body pose into {@code logicalPose()} every substep, and Sable carries
 * the tick's mobs by that pose before DT's teleport replaces it with the exact one — the gap is why
 * mobs in a carriage slid toward the back of the train (see {@link KinematicPoseTarget}). For a DT
 * carriage this overwrites the read-back position with the exact one DT drove it to, so the pose
 * mobs are carried by and the pose the blocks end the tick at are the same.
 *
 * <p>Same site as {@link SubLevelPhysicsSystemRotationLockMixin} (which clamps orientation), and
 * likewise never reached for a soft-frozen carriage: {@link SubLevelPhysicsSystemFreezeMixin}
 * cancels {@code updatePose} at {@code HEAD}. {@code latestLinearVelocity} is re-derived from the
 * corrected position with Sable's own formula so the networked velocity agrees with the pose.</p>
 *
 * <p>{@code remap = false}: Sable's own names. Verified against {@code sable-2.0.5+mc1.21.1}
 * ({@code updatePose}, {@code getPartialPhysicsTick}, {@code ServerSubLevel.getLevel}).
 * <b>Re-audit on any {@code sable_version} bump</b>, alongside the rotation-lock and freeze mixins.</p>
 */
@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class SubLevelPhysicsSystemKinematicPoseMixin {

    @Shadow
    public abstract double getPartialPhysicsTick();

    @Inject(method = "updatePose", at = @At("RETURN"))
    private void dungeonTrain$exactKinematicPose(ServerSubLevel subLevel, CallbackInfo ci) {
        if (!(subLevel instanceof DtKinematicPoseHolder holder)) return;
        KinematicPoseTarget target = holder.dt$kinematicTarget();
        if (!target.isFreshFor(subLevel.getLevel().getGameTime())) return;
        if (!TrainRotationLock.isLocked(subLevel)) return;
        Pose3d pose = subLevel.logicalPose();
        target.predict(getPartialPhysicsTick(), pose.position());
        pose.position().sub(subLevel.lastPose().position(), subLevel.latestLinearVelocity).mul(20.0);
    }
}
