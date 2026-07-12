package games.brennan.dungeontrain.ship.sable;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Removes / restores a single carriage's Rapier body from Sable's shared physics scene while
 * keeping the sub-level LOADED — the mechanism behind issue #646 (drop the ~O(bodies) physics
 * cost of carriages no client is watching). {@link PhysicsFreezeController} decides <em>which</em>
 * carriages; this class performs the pipeline op safely.
 *
 * <p><b>Why a mixin is still required.</b> {@code pipeline.remove(sl)} frees the native body but
 * leaves {@code sl.isRemoved()} == false, so Sable's per-tick native readers
 * ({@code updatePose}/{@code prePhysicsTick}/{@code applyQueuedForces}, each guarded only by
 * {@code isRemoved()}) would still touch the freed handle → segfault. The reader mixins skip any
 * sub-level whose {@link DtFreezable#dt$isPhysicsFrozen()} flag is set, which this class toggles.</p>
 *
 * <p><b>Ordering (single-threaded, server tick).</b> Freeze sets the flag <em>before</em>
 * {@code remove()} and unfreeze clears it <em>after</em> a successful {@code add()}, so a reader
 * never sees a body that is out of the scene but not flagged. The re-add mirrors Sable's own
 * blessed {@code recoverSubLevel}/{@code onSubLevelAdded} sequence:
 * {@code buildMassTracker()} → {@code pipeline.add(sl, sl.logicalPose())}.</p>
 */
public final class PhysicsFreeze {

    private static final Logger LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.jitter");

    private PhysicsFreeze() {}

    /** True while this sub-level's body is removed from the scene (readers must skip it). */
    public static boolean isFrozen(ServerSubLevel sl) {
        return sl instanceof DtFreezable f && f.dt$isPhysicsFrozen();
    }

    /**
     * Drop {@code sl}'s Rapier body from the scene (keeping the sub-level loaded). Flag is set
     * <b>before</b> {@code remove()} so the reader mixins skip it immediately; on any failure the
     * flag is rolled back and the body left in the scene.
     */
    public static void freeze(SubLevelPhysicsSystem system, ServerSubLevel sl) {
        if (isFrozen(sl)) return;
        DtFreezable flag = (DtFreezable) sl;
        flag.dt$setPhysicsFrozen(true);
        try {
            system.getPipeline().remove(sl);
        } catch (Throwable t) {
            flag.dt$setPhysicsFrozen(false); // rollback — readers resume on a still-present body
            LOGGER.warn("[freeze] remove failed for sub-level {} — left active", sl.getUniqueId(), t);
        }
    }

    /**
     * Re-add {@code sl}'s body at its logical pose (blessed sequence), then clear the flag so the
     * readers resume. If the re-add throws or produces an invalid mass, the sub-level is left
     * frozen (flag stays set) so no reader touches a body that is not soundly in the scene —
     * mirroring {@code recoverSubLevel}'s abort-on-null-COM caution.
     */
    public static void unfreeze(SubLevelPhysicsSystem system, ServerSubLevel sl) {
        if (!isFrozen(sl)) return;
        PhysicsPipeline pipeline = system.getPipeline();
        try {
            sl.buildMassTracker();
            pipeline.add(sl, sl.logicalPose());
        } catch (Throwable t) {
            LOGGER.warn("[freeze] re-add failed for sub-level {} — staying frozen", sl.getUniqueId(), t);
            return; // flag stays set → readers keep skipping (safe)
        }
        MassData mass = sl.getMassTracker();
        if (mass == null || mass.isInvalid() || mass.getCenterOfMass() == null) {
            LOGGER.warn("[freeze] re-add produced invalid mass for sub-level {} — reverting to frozen",
                sl.getUniqueId());
            try {
                pipeline.remove(sl);
            } catch (Throwable ignored) {
                // best-effort; flag stays set so readers skip it regardless
            }
            return; // stay frozen
        }
        ((DtFreezable) sl).dt$setPhysicsFrozen(false); // success → readers resume next tick
    }

    /** Hysteresis counter accessors (stored on the sub-level via the mixin). */
    public static int inactiveTicks(ServerSubLevel sl) {
        return sl instanceof DtFreezable f ? f.dt$inactiveTicks() : 0;
    }

    public static void setInactiveTicks(ServerSubLevel sl, int ticks) {
        if (sl instanceof DtFreezable f) f.dt$setInactiveTicks(ticks);
    }
}
