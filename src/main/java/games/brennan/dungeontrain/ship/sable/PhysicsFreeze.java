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
     * Drop {@code sl}'s Rapier body from the scene (keeping the sub-level loaded), marking DT-intent
     * so the reader mixins skip it. <b>Idempotent + lifecycle-safe:</b> only calls {@code remove} when
     * the body is actually in the scene ({@code dt$isInScene}). A native {@code removeSubLevel} on an
     * absent body aborts the JVM uncatchably (and a Java {@code try/catch} cannot save it), so a
     * carriage Sable is mid-spawn ({@code !inScene}) is skipped and retried next tick once Sable's
     * {@code add} fires.
     */
    public static void freeze(SubLevelPhysicsSystem system, ServerSubLevel sl) {
        if (!(sl instanceof DtFreezable flag)) return;
        if (flag.dt$isPhysicsFrozen()) return;   // already DT-frozen
        if (!flag.dt$isInScene()) return;        // not in the scene — nothing to remove (see javadoc)
        flag.dt$setPhysicsFrozen(true);          // readers skip from here
        system.getPipeline().remove(sl);         // RapierPipelineFreezeMixin flips inScene → false
    }

    /**
     * Re-add {@code sl}'s body at its logical pose (blessed sequence) and clear DT-intent so the
     * readers resume. <b>Idempotent:</b> if the body is already back in the scene (Sable re-added it),
     * just clears the flag — a second {@code add} would abort. On a Java-side re-add failure or invalid
     * mass it re-freezes so no reader touches a half-added body.
     */
    public static void unfreeze(SubLevelPhysicsSystem system, ServerSubLevel sl) {
        if (!(sl instanceof DtFreezable flag)) return;
        if (!flag.dt$isPhysicsFrozen()) return;  // not DT-frozen
        if (flag.dt$isInScene()) {               // already in the scene → adding again would double-add
            flag.dt$setPhysicsFrozen(false);
            return;
        }
        PhysicsPipeline pipeline = system.getPipeline();
        // Clear the flag BEFORE the re-add so the pipeline machinery runs ungated by the reader mixins;
        // safe (post-tick, single-threaded). Re-freeze on failure so no reader touches a half-added body.
        flag.dt$setPhysicsFrozen(false);
        try {
            sl.buildMassTracker();
            pipeline.add(sl, sl.logicalPose());  // RapierPipelineFreezeMixin flips inScene → true
        } catch (Throwable t) {
            flag.dt$setPhysicsFrozen(true);
            LOGGER.warn("[freeze] re-add failed for sub-level {} — staying frozen", sl.getUniqueId(), t);
            return;
        }
        MassData mass = sl.getMassTracker();
        if (mass == null || mass.isInvalid() || mass.getCenterOfMass() == null) {
            LOGGER.warn("[freeze] re-add produced invalid mass for sub-level {} — reverting to frozen",
                sl.getUniqueId());
            flag.dt$setPhysicsFrozen(true);
            pipeline.remove(sl); // valid: add above succeeded, body is in the scene
        }
    }

    /** Hysteresis counter accessors (stored on the sub-level via the mixin). */
    public static int inactiveTicks(ServerSubLevel sl) {
        return sl instanceof DtFreezable f ? f.dt$inactiveTicks() : 0;
    }

    public static void setInactiveTicks(ServerSubLevel sl, int ticks) {
        if (sl instanceof DtFreezable f) f.dt$setInactiveTicks(ticks);
    }
}
