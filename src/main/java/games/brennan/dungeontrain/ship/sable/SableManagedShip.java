package games.brennan.dungeontrain.ship.sable;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import games.brennan.dungeontrain.ship.InertiaSnapshot;
import games.brennan.dungeontrain.ship.KinematicDriver;
import games.brennan.dungeontrain.ship.ManagedShip;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.primitives.AABBd;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sable adapter for {@link ManagedShip}. Wraps a {@link ServerSubLevel} and
 * forwards transform queries through {@link Pose3dc#transformPosition}.
 *
 * <p><b>The sub-level is held weakly.</b> Registries keep these handles long after Sable has culled
 * the group (the train registry, portal residency, last-spawned bookkeeping), and a removed
 * {@code ServerSubLevel} still pins every chunk of its plot — Sable's {@code LevelPlot.onRemove}
 * never drops its chunk holders. A strong reference here therefore kept every carriage the train
 * ever left behind on the heap, for the whole session (~0.5 MB each, ≈400 MB/h). The stable facts
 * a stale handle is ever asked for — the id and a last-known pose — are cached in the wrapper, so
 * the object itself can go.</p>
 *
 * <p>Contract once the sub-level is gone: {@link #subLevel()} returns {@code null},
 * {@link #isResident()} is false, {@link #id()} / {@link #subLevelId()} still answer, the pose and
 * AABB queries answer from {@link LastKnownPose} (the same frozen cull-time pose a removed
 * sub-level reported before), and every mutating call is a no-op. Callers that read a live pose off
 * a registry handle already gate on {@link #isResident()}.</p>
 *
 * <p>Kinematic application: the train code (specifically
 * {@link games.brennan.dungeontrain.train.TrainWindowManager}) already calls
 * {@link #applyTickOutput} per tick, so no separate ticker is needed.
 * {@link #applyTickOutput} resets velocity then teleports + sets velocity on
 * Sable's {@link RigidBodyHandle}.</p>
 *
 * <p>{@link #setStatic} is a no-op for Sable: the per-tick teleport+velocity
 * pattern subsumes "static" behaviour — a kinematic driver can return zero
 * velocity to keep the body parked, and the teleport corrects any physics
 * drift each tick.</p>
 */
public final class SableManagedShip implements ManagedShip {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Sable can re-create a {@link ServerSubLevel} between assembly and the
     * first server tick (the integrated server saves + loads the freshly
     * assembled sub-level on world create), which gives us a different
     * reference than the one we set the driver on. The {@link SableShipyard}
     * wrapper map is keyed on those references and so creates a fresh
     * wrapper without our driver. Pinning kinematic drivers in a static map
     * by stable {@link ServerSubLevel#getUniqueId()} survives that re-creation —
     * any wrapper for the same UUID picks the driver back up.
     *
     * <p>Entries leave when a group is torn down for good ({@link #forgetDriver}) and on server
     * stop ({@link #clearDrivers}); a merely culled group keeps its driver so a reload re-attaches.</p>
     */
    private static final java.util.Map<UUID, KinematicDriver> DRIVERS_BY_UUID = new ConcurrentHashMap<>();

    private final WeakReference<ServerSubLevel> ref;
    private final UUID uuid;
    private final long id;
    private final LastKnownPose last = new LastKnownPose();
    /** Sticky: once Sable has written the sub-level to disk it stays reloadable. */
    private boolean hadSerializationPointer;

    public SableManagedShip(ServerSubLevel subLevel) {
        this(new WeakReference<>(subLevel), subLevel.getUniqueId());
        snapshot(subLevel);
    }

    /** For tests: a wrapper over an already-collected (or never-present) sub-level. */
    SableManagedShip(WeakReference<ServerSubLevel> ref, UUID uuid) {
        this.ref = ref;
        this.uuid = uuid;
        // UUID's most-significant 64 bits — collision-resistant enough for
        // per-level use (we only compare ids against ships in the same level).
        this.id = uuid.getMostSignificantBits();
    }

    /**
     * The live sub-level, or {@code null} once Sable has dropped it and the collector has taken it.
     * A removed-but-uncollected sub-level is still returned (as before); gate on
     * {@link #isResident()} for "is this carriage in play".
     */
    @Nullable
    public ServerSubLevel subLevel() {
        return ref.get();
    }

    /** Read the live pose and box into the last-known snapshot. No allocation. */
    private void snapshot(ServerSubLevel sl) {
        Pose3dc pose = sl.logicalPose();
        last.recordPose(pose.position(), pose.orientation(), pose.rotationPoint());
        BoundingBox3dc b = sl.boundingBox();
        last.recordAabb(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
        if (sl.getLastSerializationPointer() != null) hadSerializationPointer = true;
    }

    /** For tests: seed the last-known snapshot of a wrapper that has no live sub-level. */
    void seedLastKnown(Vector3dc position, Quaterniondc orientation, Vector3dc rotationPoint, AABBdc box) {
        last.recordPose(position, orientation, rotationPoint);
        last.recordAabb(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    }

    @Override
    public long id() {
        return id;
    }

    @Override
    public UUID subLevelId() {
        return uuid;
    }

    @Override
    public Vector3d worldToShip(Vector3d worldPos) {
        ServerSubLevel sl = ref.get();
        if (sl == null) return last.worldToShip(worldPos);
        return sl.logicalPose().transformPositionInverse(worldPos);
    }

    @Override
    public Vector3d shipToWorld(Vector3d modelPos) {
        ServerSubLevel sl = ref.get();
        if (sl == null) return last.shipToWorld(modelPos);
        return sl.logicalPose().transformPosition(modelPos);
    }

    @Override
    public Vector3dc currentWorldPosition() {
        ServerSubLevel sl = ref.get();
        if (sl == null) return last.position();
        snapshot(sl);
        return sl.logicalPose().position();
    }

    @Override
    public Quaterniondc currentRotation() {
        ServerSubLevel sl = ref.get();
        if (sl == null) return last.orientation();
        return sl.logicalPose().orientation();
    }

    @Override
    public Vector3dc currentPositionInModel() {
        ServerSubLevel sl = ref.get();
        if (sl == null) return last.rotationPoint();
        return sl.logicalPose().rotationPoint();
    }

    @Override
    public AABBdc worldAABB() {
        ServerSubLevel sl = ref.get();
        if (sl == null) return last.aabb();
        BoundingBox3dc b = sl.boundingBox();
        last.recordAabb(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
        return new AABBd(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
    }

    @Override
    public boolean isResident() {
        // A removed sub-level may still answer boundingBox()/logicalPose() with
        // a stale last-known pose, so registry-edge reference resolution must
        // treat it as non-resident and reload from holding instead.
        ServerSubLevel sl = ref.get();
        return sl != null && !sl.isRemoved();
    }

    @Override
    public boolean hasSerializationPointer() {
        // Sable sets this only once the sub-level has been written to disk (an
        // autosave / chunk save). Until then a cull yields a null-pointer
        // holding entry that snatchAndLoad can't revive — so DT holds the group
        // force-loaded until this is non-null. See ManagedShip#hasSerializationPointer.
        if (hadSerializationPointer) return true;
        ServerSubLevel sl = ref.get();
        if (sl != null) {
            if (sl.getLastSerializationPointer() != null) hadSerializationPointer = true;
            return hadSerializationPointer;
        }
        // The pointer Sable writes during the cull itself is the one this wrapper never saw; the
        // holding index records exactly that filing.
        return SableHoldingIndex.contains(uuid);
    }

    @Override
    @Nullable
    public KinematicDriver getKinematicDriver() {
        KinematicDriver d = DRIVERS_BY_UUID.get(uuid);
        if (d != null) return d;
        ServerSubLevel sl = ref.get();
        if (sl == null) return null;
        // Sable's FloatingBlockController can split a sub-level into pieces
        // (e.g. when carriages aren't fully connected by blocks). The split
        // pieces inherit the original sub-level's UUID via getSplitFromSubLevel().
        // Walk that chain to find the driver of the originally-driven sub-level
        // and cache it locally so subsequent lookups skip the chain walk.
        UUID origin = sl.getSplitFromSubLevel();
        while (origin != null && d == null) {
            d = DRIVERS_BY_UUID.get(origin);
            if (d != null) break;
            // Defensive bound — Sable splits should never chain more than a
            // handful of times; cap at 8 to avoid pathological infinite loops
            // if an origin chain ever cycles.
            origin = null;
        }
        if (d != null) DRIVERS_BY_UUID.put(uuid, d);
        return d;
    }

    /**
     * True if {@code subLevel} (or the sub-level it was split from) is a
     * DT-driven carriage group — i.e. DT has registered a {@link KinematicDriver}
     * for it. Every group gets a driver at assembly ({@code TrainAssembler}), and a
     * sub-level reloaded from holding keeps the same {@link ServerSubLevel#getUniqueId()}
     * key, so this stays true across culls within a server session.
     *
     * <p>Used by {@link games.brennan.dungeontrain.mixin.SubLevelHeatMapSplitMixin}
     * to suppress Sable's connectivity splitter for DT trains: a disconnected block
     * island inside a carriage group must stay welded to the group's block grid and
     * keep being teleported with the train, not spin off into its own sub-level.
     * Safe because DT trains are kinematic (per-tick teleport), not force-simulated,
     * so internal block connectivity does not affect how they move.</p>
     */
    public static boolean isDungeonTrainManaged(ServerSubLevel subLevel) {
        return driverFor(subLevel) != null;
    }

    /**
     * The {@link KinematicDriver} registered for {@code subLevel} (or for the sub-level it
     * was split from), or {@code null} if this is not a DT-driven carriage group.
     *
     * <p>The static counterpart of {@link #getKinematicDriver()}, for callers that hold a
     * bare {@link ServerSubLevel} and no wrapper — chiefly
     * {@link CarriagePivotPin#repinAfterMassChange}, which runs on Sable's per-block-change
     * choke point where allocating a wrapper per block write would be wasteful. Unlike the
     * instance method this does <em>not</em> cache the resolved driver under the split
     * sub-level's own id; it is a plain lookup, matching what
     * {@link #isDungeonTrainManaged} has always done.</p>
     */
    @Nullable
    public static KinematicDriver driverFor(ServerSubLevel subLevel) {
        if (subLevel == null) {
            return null;
        }
        KinematicDriver d = DRIVERS_BY_UUID.get(subLevel.getUniqueId());
        if (d != null) {
            return d;
        }
        UUID origin = subLevel.getSplitFromSubLevel();
        return origin == null ? null : DRIVERS_BY_UUID.get(origin);
    }

    /**
     * Drop the driver pinned for {@code subLevelId}. Only for a group that is gone for good — a
     * reload from holding re-attaches by this very key, so a merely culled group must keep it.
     */
    public static void forgetDriver(@Nullable UUID subLevelId) {
        if (subLevelId != null) DRIVERS_BY_UUID.remove(subLevelId);
    }

    /** Server stop: a single-player world switch reuses the JVM, and every driver here is that world's. */
    public static void clearDrivers() {
        DRIVERS_BY_UUID.clear();
    }

    /** How many drivers are pinned — diagnostics. */
    public static int driverCount() {
        return DRIVERS_BY_UUID.size();
    }

    @Override
    public void setKinematicDriver(KinematicDriver driver) {
        if (driver == null) {
            DRIVERS_BY_UUID.remove(uuid);
        } else {
            DRIVERS_BY_UUID.put(uuid, driver);
        }
        // Attaching a train driver is the one place DT claims a sub-level as a carriage, so it is
        // where the rotation lock goes on: from here the carriage's orientation is clamped to
        // identity on every physics substep, not just on the per-tick teleport below. See
        // DtRotationLockable.
        if (ref.get() instanceof DtRotationLockable lockable) {
            lockable.dt$setRotationLocked(TrainRotationLock.locksFor(driver));
        }
    }

    @Override
    public void setStatic(boolean isStatic) {
        // No-op for Sable. The kinematic-driver pattern (per-tick teleport +
        // velocity reset in applyTickOutput) implicitly handles "static":
        // a driver returning zero velocity + the same position each tick
        // keeps the body parked.
    }

    @Override
    public void applyTickOutput(KinematicDriver.TickOutput output) {
        ServerSubLevel subLevel = ref.get();
        if (subLevel == null) {
            LOGGER.trace("[Sable] applyTickOutput: sub-level {} is gone", uuid);
            return;
        }

        // Pin the model-space pivot FIRST, before either early return below can skip it.
        //
        // Sable's MassTracker recomputes this sub-level's centre of mass on every block change and
        // writes it into Pose3d.rotationPoint. Since the world mapping is
        // {@code position + rotation*(voxel_shipyard - rotationPoint)}, any rotationPoint drift
        // shifts ALL of the carriage's blocks — visibly, and (because rotationPoint is serialised)
        // permanently. Sable normally cancels its own recompute with a compensating body teleport,
        // but that compensation is exactly what DT's soft-freeze mixins suppress, and a frozen or
        // handle-less carriage used to fall out of this method before ever reaching the pin. So it
        // goes first and runs unconditionally.
        //
        // This does not undermine the #646 soft-freeze: the pin is three double compares and (only
        // on real drift) three double stores. The freeze's saving is the per-body Rapier work
        // below, none of which this touches. See CarriagePivotPin.
        CarriagePivotPin.pin(subLevel, output.positionInModel());

        // The last-known snapshot is what a registry handle answers from once this sub-level is
        // culled and collected; the driver's output IS the pose the body is about to take, so
        // recording it here keeps the snapshot one tick fresh at zero allocation.
        last.recordPose(output.position(), output.rotation(), output.positionInModel());
        BoundingBox3dc b = subLevel.boundingBox();
        last.recordAabb(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());

        // A DT-frozen carriage (#646 soft-freeze) has been parked: its body stays in the physics
        // scene, but DT stops teleporting it here so it sits at rest while Sable does no per-body work
        // for it. Skipping this per-tick teleport + velocity write IS the park (and part of the
        // soft-freeze saving); PhysicsFreeze.freeze already zeroed its velocity so it won't drift.
        //
        // The POSE, though, keeps following the driver: a plain Java write into logicalPose() plus a
        // bounding-box refresh, no native call. Sable's tracking system decides from that pose whether
        // a player is close enough to track the carriage, so leaving it at the parked spot meant a
        // group whose true slot was in front of the player was never re-tracked, never unfrozen, and
        // simply missing from the train (see PhysicsFreeze). It resumes teleporting the tick after
        // PhysicsFreeze.unfreeze clears the flag — onto this already-correct pose, so nothing jumps.
        // The networked velocity fields below stay untouched here: they are gated on tracking
        // players, and a frozen carriage has none by definition.
        if (PhysicsFreeze.isFrozen(subLevel)) {
            PhysicsFreeze.followParked(subLevel, output.position(), output.rotation());
            return;
        }

        RigidBodyHandle handle = RigidBodyHandle.of(subLevel);
        if (handle == null || !handle.isValid()) {
            LOGGER.trace("[Sable] applyTickOutput: handle invalid for sub-level {}", uuid);
            return;
        }

        // Teleport overrides the body's pose authoritatively. Sable's renderer
        // interpolates between lastPose (set during the just-completed tick)
        // and the current pose, so the visual motion is smooth as long as
        // applyTickOutput fires every tick.
        handle.teleport(output.position(), output.rotation());

        // Reset velocity to zero, then add the driver's chosen velocity.
        // Sable has no direct `setVelocity` — only `addLinearAndAngularVelocity`
        // — so we negate the current velocity first.
        Vector3d curLin = new Vector3d();
        Vector3d curAng = new Vector3d();
        handle.getLinearVelocity(curLin);
        handle.getAngularVelocity(curAng);
        handle.addLinearAndAngularVelocity(curLin.negate(), curAng.negate());
        handle.addLinearAndAngularVelocity(output.linearVelocity(), output.angularVelocity());

        // Pure-kinematic enforcement: wipe any queued forces (gravity, drag,
        // lift, propulsion) that Sable's per-frame providers scheduled for
        // this body before the next physics tick reads them. With this and
        // the teleport above, no impulse can leak through to push or tilt
        // the train between server ticks. The map is null until Sable lazily
        // creates it on first force queueing — null means nothing to clear.
        java.util.Map<ForceGroup, QueuedForceGroup> queued = subLevel.getQueuedForceGroups();
        if (queued != null) {
            for (QueuedForceGroup group : queued.values()) {
                group.reset();
            }
        }

        // Mirror our kinematic intent into the sublevel's networked velocity
        // fields so clients carry the prescribed motion. These are public
        // final Vector3d's — references stay; only contents change.
        //
        // Gate the write on there being at least one tracking client. A
        // carriage force-held resident (or catching up after a holding
        // reload) with no client in range would otherwise have these velocity
        // fields networked to nobody — and Sable logs "Received a sub-level
        // movement packet for a non-existent sub-level" when a client that has
        // culled the sub-level receives that stale movement. The server-side
        // teleport above still runs unconditionally, so a client that
        // re-enters tracking range gets a correct full-sync pose from Sable's
        // own tracking system — only the redundant, desync-triggering velocity
        // packet to non-tracking clients is suppressed.
        if (!subLevel.getTrackingPlayers().isEmpty()) {
            subLevel.latestLinearVelocity.set(output.linearVelocity());
            subLevel.latestAngularVelocity.set(output.angularVelocity());
        }
    }

    @Override
    @Nullable
    public InertiaSnapshot captureInertia() {
        ServerSubLevel subLevel = ref.get();
        if (subLevel == null) return null;
        MassData mass = subLevel.getMassTracker();
        if (mass.isInvalid()) {
            return null;
        }
        Vector3dc com = mass.getCenterOfMass();
        if (com == null) {
            // Empty / invalid mass tracker — no blocks contributed to mass.
            return null;
        }
        return new InertiaSnapshot(com, mass.getMass(), mass.getInertiaTensor());
    }

    @Override
    public void restoreInertia(@Nullable InertiaSnapshot snapshot) {
        // Sable's MassTracker is read-only, so there is nothing to restore through this API.
        //
        // Note this is NOT because the pivot-drift problem went away on Sable — block mutations
        // very much do recompute physics state, synchronously, inside LevelChunk.setBlockState.
        // It is because the defence moved: CarriagePivotPin re-pins Pose3d.rotationPoint both on
        // Sable's per-block-change choke point and at the top of applyTickOutput, which covers
        // frozen and handle-less carriages that this API never reached anyway.
    }

    @Override
    public String toString() {
        return "SableManagedShip{" + uuid + (isResident() ? ", resident" : ", stale") + '}';
    }
}
