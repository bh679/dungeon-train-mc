package games.brennan.dungeontrain.mixin;

import dev.ryanhcode.sable.api.physics.mass.MergedMassTracker;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import games.brennan.dungeontrain.ship.sable.TrainRotationLock;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops a DT carriage's pivot moving when its mass changes — the other half of "building can't
 * disturb the train", alongside the rotation lock.
 *
 * <p><b>The problem.</b> Sable recomputes a sub-level's centre of mass on every block change and
 * writes it into {@code Pose3d.rotationPoint}. The world mapping is
 * {@code position + R·(voxel_shipyard − rotationPoint)}, so moving the pivot translates <em>every
 * block in the carriage</em>. Deleting one side of a carriage moved it half a block, in ~90 steps,
 * one per block broken — visible as jitter while building.
 *
 * <p><b>The single writer.</b> {@code MergedMassTracker.uploadData()} (bytecode-verified against
 * {@code sable-2.0.5+mc1.21.1}) is where that chain starts: when the centre of mass or inertia
 * differs from the last upload it stamps {@code lastCenterOfMass}/{@code lastInertiaTensor}, calls
 * {@code SubLevelPhysicsSystem.updatePose}, reads {@code logicalPose()} and issues a compensating
 * body teleport through the pipeline. Cancelling it for a DT carriage means the pivot simply never
 * moves and no compensation is ever needed.
 *
 * <p>Only the <em>push</em> is skipped — {@code update(float)} still runs, so the tracker's own
 * mass/COM/inertia fields stay live for {@code SableManagedShip.captureInertia} and any other
 * reader. The native body keeps the mass it had, which steers nothing: DT teleports a carriage,
 * zeroes its velocity and clears its queued forces every tick.
 *
 * <p>{@link games.brennan.dungeontrain.ship.sable.CarriagePivotPin} stays in place behind this as
 * defence-in-depth for any path this guard does not cover, and its {@code [pinCorrected]} log line
 * is the tripwire: a large drift there means the guard stopped applying.
 *
 * <p>{@code remap = false}: Sable's own names, and {@code uploadData} is private — a Sable refactor
 * could rename or inline it. <b>Re-audit on any {@code sable_version} bump</b>, with the freeze and
 * rotation-lock mixins.</p>
 */
@Mixin(value = MergedMassTracker.class, remap = false)
public abstract class MergedMassTrackerPivotFreezeMixin {

    @Shadow @Final private ServerSubLevel subLevel;

    @Inject(method = "uploadData", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$freezeLockedCarriagePivot(CallbackInfo ci) {
        if (TrainRotationLock.isLocked(subLevel)) ci.cancel();
    }
}
