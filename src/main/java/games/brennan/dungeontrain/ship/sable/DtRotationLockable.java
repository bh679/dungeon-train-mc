package games.brennan.dungeontrain.ship.sable;

/**
 * Duck-type interface mixed into Sable's {@code ServerSubLevel} (by
 * {@link games.brennan.dungeontrain.mixin.ServerSubLevelFreezeMixin}, which already carries DT's
 * other per-instance physics flags) so DT can mark a sub-level as <b>rotation-locked</b> —
 * permanently axis-aligned, orientation identity.
 *
 * <p><b>Why.</b> A DT carriage is kinematic: {@code TrainTransformProvider.nextTransform} always
 * returns identity rotation and {@link SableManagedShip#applyTickOutput} teleports the body onto
 * that pose once per <em>server</em> tick. Sable, though, steps physics {@code substepsPerTick}
 * times inside that tick (see {@link PhysicsSubstepTuner}), and each substep writes the freshly
 * integrated pose into {@code logicalPose()}. Gravity torque about a centre of mass a player's
 * build has shifted — or a collision impulse — therefore tilts the carriage <em>between</em> DT's
 * corrections, and collision, tracking, networking and the renderer all read that tilted pose. The
 * lock closes that window: every substep is clamped back to identity.</p>
 *
 * <p>Set in {@link SableManagedShip#setKinematicDriver} — the one place DT claims a sub-level as a
 * train carriage — and read on the hot physics path (O(bodies × substeps) per tick), so it must
 * stay a plain field read on the instance rather than a map lookup, exactly like
 * {@link DtFreezable}.</p>
 *
 * <p>Read/written on the server thread only. See {@link TrainRotationLock} for the read helper and
 * {@link games.brennan.dungeontrain.mixin.SubLevelPhysicsSystemRotationLockMixin} /
 * {@link games.brennan.dungeontrain.mixin.RapierPipelineRotationLockMixin} for the two clamps.</p>
 */
public interface DtRotationLockable {

    /** True while this sub-level is a DT-driven carriage whose orientation must stay identity. */
    boolean dt$isRotationLocked();

    /** Set by {@link SableManagedShip#setKinematicDriver} when a train driver is attached/removed. */
    void dt$setRotationLocked(boolean locked);
}
