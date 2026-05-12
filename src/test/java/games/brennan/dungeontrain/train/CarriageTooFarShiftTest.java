package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the post-spawn placement-tracker's "too far apart" branch
 * in {@link TrainCarriageAppender}.
 *
 * <p>Exercises the two pure decision helpers:
 * <ul>
 *   <li>{@link TrainCarriageAppender#placementTrackerShiftDx} — direction
 *       selection given (colliding, pIdx pair, gap, spawn direction).</li>
 *   <li>{@link TrainCarriageAppender#facingGapBetween} — primitive-arg AABB
 *       gap math (AABBdc is not on the test classpath; the production
 *       wrapper {@code gapToTrainFacingSibling} unpacks AABBdc fields into
 *       these primitives).</li>
 * </ul>
 *
 * <p>The runtime tracker itself ({@code runPlacementCollisionTracker}) depends
 * on Sable's {@code ManagedShip.worldAABB()} and the live train registry — it
 * isn't unit-testable without a Minecraft bootstrap. These pure helpers cover
 * the math; in-game testing covers the integration.</p>
 */
final class CarriageTooFarShiftTest {

    private static final double SHIFT = 0.5;
    private static final double EPS = 1e-9;

    // ───────────────────────────────────────────────────────────────
    // placementTrackerShiftDx — direction selection
    // ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("collision with offender ahead → shift -X away")
    void collidingOffenderAhead_shiftsBackward() {
        double dx = TrainCarriageAppender.placementTrackerShiftDx(
            true, 5, 6, Double.POSITIVE_INFINITY, false, false);
        assertEquals(-SHIFT, dx, EPS);
    }

    @Test
    @DisplayName("collision with offender behind → shift +X away")
    void collidingOffenderBehind_shiftsForward() {
        double dx = TrainCarriageAppender.placementTrackerShiftDx(
            true, 5, 4, Double.POSITIVE_INFINITY, false, false);
        assertEquals(+SHIFT, dx, EPS);
    }

    @Test
    @DisplayName("forward spawn, gap=5.0 (too far) → shift -X toward train")
    void forwardSpawnTooFar_shiftsBackwardTowardTrain() {
        double dx = TrainCarriageAppender.placementTrackerShiftDx(
            false, 5, 0, 5.0, false, false);
        assertEquals(-SHIFT, dx, EPS);
    }

    @Test
    @DisplayName("backward spawn, gap=5.0 (too far) → shift +X toward train")
    void backwardSpawnTooFar_shiftsForwardTowardTrain() {
        double dx = TrainCarriageAppender.placementTrackerShiftDx(
            false, 5, 0, 5.0, true, false);
        assertEquals(+SHIFT, dx, EPS);
    }

    @Test
    @DisplayName("gap=2.5 (inside dead-band) → no shift, clean tick")
    void gapInsideDeadBand_noShift() {
        double dx = TrainCarriageAppender.placementTrackerShiftDx(
            false, 5, 0, 2.5, false, false);
        assertEquals(0.0, dx, EPS);
    }

    @Test
    @DisplayName("gap=3.0 exactly (boundary) → no shift, clean tick")
    void gapAtBoundary_noShift() {
        double dx = TrainCarriageAppender.placementTrackerShiftDx(
            false, 5, 0, 3.0, false, false);
        assertEquals(0.0, dx, EPS);
    }

    @Test
    @DisplayName("gap=∞ (no sibling on facing side) → no shift, clean tick")
    void gapInfinite_noShift() {
        double dx = TrainCarriageAppender.placementTrackerShiftDx(
            false, 5, 0, Double.POSITIVE_INFINITY, false, false);
        assertEquals(0.0, dx, EPS);
    }

    @Test
    @DisplayName("collision takes precedence over gap-too-far")
    void collisionOverridesTooFar() {
        // If somehow both colliding=true AND gap measurement reports a large
        // value (shouldn't happen since the production caller short-circuits
        // gap to 0.0 when colliding, but the pure helper is defensive),
        // collision branch wins.
        double dx = TrainCarriageAppender.placementTrackerShiftDx(
            true, 5, 6, 10.0, false, false);
        assertEquals(-SHIFT, dx, EPS);
    }

    // ───────────────────────────────────────────────────────────────
    // moveTogetherLocked — collide→move-together→collide cycle lock
    // ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("locked, gap=5.0 (too far) → no shift (move-together suppressed)")
    void locked_tooFar_suppressesMoveTogether() {
        double dx = TrainCarriageAppender.placementTrackerShiftDx(
            false, 5, 0, 5.0, false, true);
        assertEquals(0.0, dx, EPS);
    }

    @Test
    @DisplayName("locked, backward spawn, gap=5.0 → no shift (move-together suppressed)")
    void locked_backwardTooFar_suppressesMoveTogether() {
        double dx = TrainCarriageAppender.placementTrackerShiftDx(
            false, 5, 0, 5.0, true, true);
        assertEquals(0.0, dx, EPS);
    }

    @Test
    @DisplayName("locked, colliding ahead → still shifts -X away (lock does NOT gate collision pushback)")
    void locked_collidingAhead_stillShiftsAway() {
        double dx = TrainCarriageAppender.placementTrackerShiftDx(
            true, 5, 6, Double.POSITIVE_INFINITY, false, true);
        assertEquals(-SHIFT, dx, EPS);
    }

    @Test
    @DisplayName("locked, colliding behind → still shifts +X away (lock does NOT gate collision pushback)")
    void locked_collidingBehind_stillShiftsAway() {
        double dx = TrainCarriageAppender.placementTrackerShiftDx(
            true, 5, 4, Double.POSITIVE_INFINITY, false, true);
        assertEquals(+SHIFT, dx, EPS);
    }

    @Test
    @DisplayName("locked, gap inside dead-band → no shift (same as unlocked)")
    void locked_gapInDeadBand_noShift() {
        double dx = TrainCarriageAppender.placementTrackerShiftDx(
            false, 5, 0, 2.5, false, true);
        assertEquals(0.0, dx, EPS);
    }

    // ───────────────────────────────────────────────────────────────
    // facingGapBetween — AABB geometry, primitive-arg form
    // ───────────────────────────────────────────────────────────────

    /** Build a forward-spawn self carriage AABB: self.minX = 10. */
    private static final double SELF_MIN_X = 10.0;
    private static final double SELF_MAX_X = 19.0;
    private static final double SELF_MIN_Y = 64.0;
    private static final double SELF_MAX_Y = 68.0;
    private static final double SELF_MIN_Z = 0.0;
    private static final double SELF_MAX_Z = 5.0;

    @Test
    @DisplayName("forward spawn — sibling 2 blocks behind → gap=2.0")
    void forward_siblingBehind_returnsXGap() {
        // Sibling: maxX = 8.0 → gap = 10.0 - 8.0 = 2.0
        double gap = TrainCarriageAppender.facingGapBetween(
            SELF_MIN_X, SELF_MAX_X, SELF_MIN_Y, SELF_MAX_Y, SELF_MIN_Z, SELF_MAX_Z,
            -1.0, 8.0, 64.0, 68.0, 0.0, 5.0,
            false);
        assertEquals(2.0, gap, EPS);
    }

    @Test
    @DisplayName("forward spawn — sibling 5 blocks behind → gap=5.0 (too-far range)")
    void forward_siblingFarBehind_returnsXGap() {
        double gap = TrainCarriageAppender.facingGapBetween(
            SELF_MIN_X, SELF_MAX_X, SELF_MIN_Y, SELF_MAX_Y, SELF_MIN_Z, SELF_MAX_Z,
            -10.0, 5.0, 64.0, 68.0, 0.0, 5.0,
            false);
        assertEquals(5.0, gap, EPS);
    }

    @Test
    @DisplayName("forward spawn — sibling AHEAD (wrong side) → POSITIVE_INFINITY")
    void forward_siblingAhead_returnsInfinity() {
        // Sibling at minX=20, well past self.maxX=19 — not on train-facing side.
        double gap = TrainCarriageAppender.facingGapBetween(
            SELF_MIN_X, SELF_MAX_X, SELF_MIN_Y, SELF_MAX_Y, SELF_MIN_Z, SELF_MAX_Z,
            20.0, 30.0, 64.0, 68.0, 0.0, 5.0,
            false);
        assertTrue(Double.isInfinite(gap) && gap > 0);
    }

    @Test
    @DisplayName("forward spawn — sibling above (wrong Y lane) → POSITIVE_INFINITY")
    void forward_siblingWrongYLane_returnsInfinity() {
        // Sibling Y = [200, 204] — doesn't overlap self's Y = [64, 68].
        double gap = TrainCarriageAppender.facingGapBetween(
            SELF_MIN_X, SELF_MAX_X, SELF_MIN_Y, SELF_MAX_Y, SELF_MIN_Z, SELF_MAX_Z,
            -10.0, 5.0, 200.0, 204.0, 0.0, 5.0,
            false);
        assertTrue(Double.isInfinite(gap) && gap > 0);
    }

    @Test
    @DisplayName("forward spawn — sibling on parallel Z lane → POSITIVE_INFINITY")
    void forward_siblingWrongZLane_returnsInfinity() {
        // Sibling Z = [10, 15] — doesn't overlap self's Z = [0, 5].
        double gap = TrainCarriageAppender.facingGapBetween(
            SELF_MIN_X, SELF_MAX_X, SELF_MIN_Y, SELF_MAX_Y, SELF_MIN_Z, SELF_MAX_Z,
            -10.0, 5.0, 64.0, 68.0, 10.0, 15.0,
            false);
        assertTrue(Double.isInfinite(gap) && gap > 0);
    }

    @Test
    @DisplayName("backward spawn — sibling 2 blocks ahead → gap=2.0")
    void backward_siblingAhead_returnsXGap() {
        // Backward-spawn self faces +X; sibling at minX=21 → gap = 21 - 19 = 2.0
        double gap = TrainCarriageAppender.facingGapBetween(
            SELF_MIN_X, SELF_MAX_X, SELF_MIN_Y, SELF_MAX_Y, SELF_MIN_Z, SELF_MAX_Z,
            21.0, 30.0, 64.0, 68.0, 0.0, 5.0,
            true);
        assertEquals(2.0, gap, EPS);
    }

    @Test
    @DisplayName("backward spawn — sibling BEHIND (wrong side) → POSITIVE_INFINITY")
    void backward_siblingBehind_returnsInfinity() {
        double gap = TrainCarriageAppender.facingGapBetween(
            SELF_MIN_X, SELF_MAX_X, SELF_MIN_Y, SELF_MAX_Y, SELF_MIN_Z, SELF_MAX_Z,
            -10.0, 5.0, 64.0, 68.0, 0.0, 5.0,
            true);
        assertTrue(Double.isInfinite(gap) && gap > 0);
    }

    @Test
    @DisplayName("Y-edge touching counts as overlap (open interval semantics)")
    void yLaneEdgeTouching_treatedAsOverlap() {
        // Self Y = [64, 68]; sibling Y = [60, 64]. The open-interval test
        // (selfMaxY > otherMinY) → 68 > 60 ✓; (selfMinY < otherMaxY) → 64 < 64 ✗.
        // So edge-touching at Y=64 should NOT count as same-lane.
        double gap = TrainCarriageAppender.facingGapBetween(
            SELF_MIN_X, SELF_MAX_X, SELF_MIN_Y, SELF_MAX_Y, SELF_MIN_Z, SELF_MAX_Z,
            -10.0, 5.0, 60.0, 64.0, 0.0, 5.0,
            false);
        assertTrue(Double.isInfinite(gap) && gap > 0);
    }
}
