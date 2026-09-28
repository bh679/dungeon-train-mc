package games.brennan.dungeontrain.ship.sable;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import games.brennan.dungeontrain.ship.KinematicDriver;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps a DT carriage's model-space pivot ({@code Pose3d.rotationPoint}) welded to the value its
 * kinematic driver locked at spawn, so no change to the carriage's blocks can move the carriage.
 *
 * <p><b>Why this is needed.</b> A sub-level's blocks are mapped into the world as
 * {@code world = position + R·(voxel_shipyard − rotationPoint)}. Sable's {@code MergedMassTracker}
 * recomputes the sub-level's centre of mass on every block change and writes it into
 * {@code rotationPoint}, so <em>any</em> pivot movement translates every block in the carriage at
 * once. Sable's own {@code uploadData} compensates for this — it teleports the body by {@code R·Δ}
 * in the same breath, and the two cancel — but that compensation runs through
 * {@code SubLevelPhysicsSystem.updatePose} and {@code pipeline.teleport}, both of which DT cancels
 * for a soft-frozen carriage ({@link games.brennan.dungeontrain.mixin.SubLevelPhysicsSystemFreezeMixin},
 * {@link games.brennan.dungeontrain.mixin.RapierPipelineFreezeMixin}). Nothing cancels the
 * {@code rotationPoint} write itself — it is a plain JOML field store. So a frozen carriage that
 * loses blocks keeps its old {@code position}, takes on a new {@code rotationPoint}, and is
 * translated by {@code −R·Δ} <em>permanently</em>: {@code SubLevelSerializer} persists
 * {@code rotationPoint}, so the shift even survives save/load.</p>
 *
 * <p>Correcting this is safe precisely because DT trains are kinematic — {@code applyTickOutput}
 * teleports each carriage every tick, forces identity rotation, zeroes velocity and wipes queued
 * forces — so where the physics engine believes the centre of mass to be has no bearing on how the
 * train moves. Only the block-to-world mapping matters, and that is what the pivot controls.</p>
 *
 * <p><b>Threading.</b> Every writer is the server thread: {@link #pin} is reached from
 * {@code SableManagedShip.applyTickOutput}, whose sole caller is {@code SableKinematicTicker}'s
 * {@code LevelTickEvent.Post} subscriber, and {@link #repinAfterMassChange} from Sable's
 * {@code LevelChunkMixin} on {@code LevelChunk.setBlockState}. There is no cross-thread access to
 * guard, which is the only reason writing straight into the live mutable {@code Vector3d} behind
 * {@code Pose3d.rotationPoint()} is sound.</p>
 *
 * <p><b>{@code IN_PHYSICS_STEP}.</b> Sable sets this around its synchronous
 * {@code pipeline.physicsTick(dt)} and passes {@code !IN_PHYSICS_STEP} to
 * {@code updateMassDataFromBlockChange} as the gate on its whole pose-writing branch — a block
 * change occurring inside the native step therefore never moves {@code rotationPoint} and needs no
 * correction. {@link #repinAfterMassChange} bails on it anyway, because the body restore it may
 * perform is a native Rapier {@code teleport} and re-entering Rapier from inside {@code physicsTick}
 * is not safe.</p>
 *
 * <p><b>Load-bearing Sable assumption:</b> {@code Pose3d.rotationPoint()} must keep returning the
 * <em>live mutable</em> {@code Vector3d} — there is no setter for it, so if a future Sable returns a
 * copy the pin silently becomes a no-op and the bug returns without a compile error. Re-verify that,
 * plus {@code SubLevelPhysicsSystem.IN_PHYSICS_STEP} and {@code RigidBodyHandle.of/isValid/teleport},
 * on any {@code sable_version} bump. Verified against {@code sable-2.0.2+mc1.21.1}.</p>
 *
 * <p><b>Since the mass-upload guard landed, this class is a backstop rather than the primary
 * defence.</b> {@code MergedMassTrackerPivotFreezeMixin} cancels {@code MergedMassTracker.uploadData}
 * for a DT carriage, so the pivot no longer moves in the first place and there is no compensating
 * teleport to undo — that removed the jitter this after-the-fact correction could only chase
 * (deleting one side of a carriage used to walk the pivot half a block, ~90 corrections deep). The
 * pin stays for any path the guard does not cover, which makes its {@code [pinCorrected]} log line a
 * tripwire: a drift materially above {@link #PIVOT_EPSILON} means the guard stopped applying (a
 * Sable refactor of {@code uploadData}, say) and should be investigated, not ignored.</p>
 */
public final class CarriagePivotPin {

    /**
     * Sub-millimetre. Matches {@code TrainTransformProvider.PIVOT_MOVE_EPSILON} so the pin and the
     * jitter probe agree on what counts as the pivot having moved; below this is floating-point
     * round-off, and a real centre-of-mass recompute produces deltas orders of magnitude larger.
     */
    static final double PIVOT_EPSILON = 1e-6;

    /** Shares the jitter probe's logger, which {@code DungeonTrain}'s constructor elevates to DEBUG. */
    private static final Logger JITTER_LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.jitter");

    /** What {@link #repinAfterMassChange} should do once the pivot has been examined. */
    enum Action {
        /** Pivot had not drifted — nothing to correct. */
        SKIP,
        /** Pin the pivot and stop: Sable's compensating body teleport never happened. */
        PIN_ONLY,
        /** Pin the pivot and undo the compensating body teleport Sable just issued. */
        PIN_AND_RESTORE
    }

    private CarriagePivotPin() {
    }

    /**
     * Whether the three gates that precede any pivot read are clear. Pure so the branch matrix is
     * unit-testable without a Minecraft or Sable bootstrap.
     *
     * @param inPhysicsStep  Sable is inside its native step — it did not write the pivot, and we
     *                       must not re-enter Rapier
     * @param hasDriver      the sub-level is a DT-driven carriage
     * @param hasLockedPivot the driver has captured its spawn pivot (false before the first
     *                       kinematic tick, when there is nothing to pin to)
     */
    static boolean shouldAttemptPin(boolean inPhysicsStep, boolean hasDriver, boolean hasLockedPivot) {
        return !inPhysicsStep && hasDriver && hasLockedPivot;
    }

    /** True if {@code (rx, ry, rz)} differs from {@code (tx, ty, tz)} by more than {@link #PIVOT_EPSILON}. */
    static boolean pivotDrifted(double rx, double ry, double rz, double tx, double ty, double tz) {
        return Math.abs(rx - tx) > PIVOT_EPSILON
            || Math.abs(ry - ty) > PIVOT_EPSILON
            || Math.abs(rz - tz) > PIVOT_EPSILON;
    }

    /**
     * What to do after the pivot has been examined and (if it drifted) pinned.
     *
     * <p>A frozen carriage takes {@link Action#PIN_ONLY}: DT cancelled both {@code updatePose} and
     * {@code pipeline.teleport} for it, so its {@code position} was never compensated and restoring
     * the pivot alone puts every block back exactly where it was. An unfrozen carriage did receive
     * Sable's compensating teleport, so that has to be undone too — otherwise re-pinning the pivot
     * without moving the body back leaves the blocks off by {@code +R·Δ}, trading one shift for
     * another. A carriage with no valid handle cannot be teleported at all, so it degrades to
     * {@link Action#PIN_ONLY}.</p>
     */
    static Action decideRestore(boolean drifted, boolean frozen, boolean handleValid) {
        if (!drifted) return Action.SKIP;
        if (frozen || !handleValid) return Action.PIN_ONLY;
        return Action.PIN_AND_RESTORE;
    }

    /**
     * Pin {@code subLevel}'s pivot to {@code pivotTarget}, returning whether it had actually drifted.
     *
     * <p>Three double compares and (only on drift) three double stores — no native call, no Sable
     * subsystem, nothing the soft-freeze is trying to avoid. That is what makes it safe to run for a
     * frozen carriage.</p>
     *
     * @return {@code null} if there was no drift to correct, otherwise the drift that was corrected
     *         (current minus target), for diagnostics
     */
    @Nullable
    private static Vector3d pinAndMeasure(ServerSubLevel subLevel, @Nullable Vector3dc pivotTarget) {
        if (pivotTarget == null) return null;
        if (!(subLevel.logicalPose() instanceof Pose3d livePose)) return null;
        Vector3d rotationPoint = livePose.rotationPoint();
        if (!pivotDrifted(rotationPoint.x, rotationPoint.y, rotationPoint.z,
                pivotTarget.x(), pivotTarget.y(), pivotTarget.z())) {
            return null;
        }
        Vector3d drift = new Vector3d(
            rotationPoint.x - pivotTarget.x(),
            rotationPoint.y - pivotTarget.y(),
            rotationPoint.z - pivotTarget.z());
        rotationPoint.set(pivotTarget.x(), pivotTarget.y(), pivotTarget.z());
        return drift;
    }

    /**
     * Pin {@code subLevel}'s pivot to {@code pivotTarget}. The per-tick entry point, called from
     * {@code SableManagedShip.applyTickOutput} <em>before</em> its freeze and handle-validity
     * early-returns so that a parked or handle-less carriage is defended too.
     *
     * @return whether the pivot had drifted and was corrected
     */
    public static boolean pin(ServerSubLevel subLevel, @Nullable Vector3dc pivotTarget) {
        return pinAndMeasure(subLevel, pivotTarget) != null;
    }

    /**
     * Undo a Sable mass recompute for a DT carriage, synchronously, on Sable's per-block-change
     * choke point — before anything can observe the moved pivot.
     *
     * <p>No-ops for every block change outside a DT carriage, and for the overwhelming majority of
     * changes inside one (placing a block back, a redstone flip, anything that leaves the centre of
     * mass where it was), at the cost of one map lookup and three double compares.</p>
     */
    public static void repinAfterMassChange(ServerSubLevel subLevel) {
        if (SubLevelPhysicsSystem.IN_PHYSICS_STEP) return;

        KinematicDriver driver = SableManagedShip.driverFor(subLevel);
        Vector3dc locked = driver == null ? null : driver.lockedPositionInModel();
        if (!shouldAttemptPin(false, driver != null, locked != null)) return;

        Vector3d drift = pinAndMeasure(subLevel, locked);
        if (drift == null) return;

        driver.onExternalMassChange();

        boolean frozen = PhysicsFreeze.isFrozen(subLevel);
        RigidBodyHandle handle = frozen ? null : RigidBodyHandle.of(subLevel);
        boolean handleValid = handle != null && handle.isValid();
        Action action = decideRestore(true, frozen, handleValid);

        if (action == Action.PIN_AND_RESTORE) {
            // Cancel the compensating teleport MergedMassTracker.uploadData issued for the centre-of-
            // mass delta. logicalPose() still holds the pre-compensation position — Sable teleported
            // the native body, and updatePose only writes back on the next physics pass — so feeding
            // it straight back is what restores the body to where the pinned pivot expects it.
            Pose3dc pose = subLevel.logicalPose();
            handle.teleport(pose.position(), pose.orientation());
        }

        JITTER_LOGGER.debug(
            "[pinCorrected] subLevel={} frozen={} action={} drift=({}, {}, {})",
            subLevel.getUniqueId(), frozen, action,
            String.format("%.6f", drift.x), String.format("%.6f", drift.y), String.format("%.6f", drift.z));
    }
}
