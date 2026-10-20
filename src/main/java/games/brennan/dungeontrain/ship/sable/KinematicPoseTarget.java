package games.brennan.dungeontrain.ship.sable;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Where a DT carriage's body is about to be, in double precision, so its pose need not come from
 * Rapier's single-precision read-back.
 *
 * <p>Sable steps physics at the top of the server tick and copies the native body pose back into
 * {@code logicalPose()} ({@code SubLevelPhysicsSystem.updatePose}). Rapier holds that pose in
 * {@code f32}, so the read-back lands a few float steps short of where DT drove the carriage —
 * 1e-4 of a block a tick near the origin, ⅛ of a block at x≈1M. Then the tick's entities run and
 * Sable carries every mob standing in the carriage by that float pose. Only afterwards, on
 * {@code LevelTickEvent.Post}, does {@link SableManagedShip#applyTickOutput}'s teleport write the
 * exact double pose into {@code logicalPose()} ({@code RapierPhysicsPipeline.teleport} sets it
 * directly), snapping the blocks forward past the mobs. The gap was never given back to them, so
 * anything standing in a carriage slid toward the back of the train a little every tick — a drift
 * that grew with distance from x=0 (−0.14 b/s at x≈494k, speed 2). Players never saw it: their
 * carry is client-side, from the exact poses the server networks.</p>
 *
 * <p>A DT carriage is kinematic, so the exact answer is already known: the position it was last
 * teleported to, plus the velocity it was given, times the physics time elapsed since.
 * {@link SableManagedShip#applyTickOutput} {@link #record}s the first two;
 * {@code SubLevelPhysicsSystemKinematicPoseMixin} writes {@link #predict} over each substep's
 * read-back. A record from any tick but the previous one is stale (the carriage was frozen, or its
 * handle was invalid, so nothing teleported it) and is ignored, leaving Sable's own read.</p>
 *
 * <p>Plain doubles, no Minecraft types, so it is unit-testable. Touched only on the server thread.</p>
 */
public final class KinematicPoseTarget {

    /** One server tick, in seconds — the span Sable's substeps divide. */
    static final double TICK_SECONDS = 0.05;

    private final Vector3d position = new Vector3d();
    private final Vector3d velocity = new Vector3d();
    private long recordedTick = Long.MIN_VALUE;

    /**
     * Note the pose DT just teleported the body to and the velocity it set (blocks per second),
     * on game tick {@code gameTick}.
     */
    public void record(Vector3dc teleportedTo, Vector3dc velocityPerSecond, long gameTick) {
        position.set(teleportedTo);
        velocity.set(velocityPerSecond);
        recordedTick = gameTick;
    }

    /**
     * Whether a record taken on {@code gameTick} still describes the body. DT records on
     * {@code LevelTickEvent.Post}, after the level has advanced its clock, and Sable steps physics
     * at the head of the next level tick, before it advances again — so a live record carries the
     * same game time the physics step reads.
     */
    public boolean isFreshFor(long gameTick) {
        return recordedTick == gameTick;
    }

    /**
     * The body's exact position {@code partialTick} of the way through the coming tick (Sable's
     * {@code getPartialPhysicsTick()}: 1/n … n/n over n substeps). Clamped to [0, 1], since Sable
     * answers past 1 once its substep loop has finished.
     */
    public Vector3d predict(double partialTick, Vector3d dest) {
        double t = Math.max(0.0, Math.min(1.0, partialTick)) * TICK_SECONDS;
        return velocity.mulAdd(t, position, dest);
    }
}
