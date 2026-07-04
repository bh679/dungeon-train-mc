package games.brennan.dungeontrain.train;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the pivot-drift compensation math extracted from
 * {@link TrainTransformProvider#computeEffectivePosition}. The helper is pure
 * (JOML in, JOML out) so tests run without a Forge/Minecraft bootstrap or any
 * Valkyrien Skies runtime.
 *
 * <p>The full {@code computeCompensatedTransform} method additionally calls
 * {@code current.toBuilder().positionInModel(...)} to override VS's pivot.
 * That interaction is not unit-tested — the empirical test is Gate 2b's log
 * check (rawComDeltaX stays ≈ 0 while the train runs), confirming that VS
 * honours the supplied positionInModel at runtime.</p>
 */
final class TrainTransformProviderTest {

    private static final double EPS = 1e-9;

    @Test
    @DisplayName("zero drift → effectivePos equals canonicalPos")
    void computeEffectivePosition_zeroDrift_returnsCanonicalPos() {
        Vector3d canonical = new Vector3d(100, 64, -50);
        Vector3d pivot = new Vector3d(-2.867e7, 127.8, 1.229e7);
        Quaterniond identity = new Quaterniond();

        Vector3d result = TrainTransformProvider.computeEffectivePosition(
            canonical, pivot, pivot, identity);

        assertEquals(canonical.x, result.x, EPS);
        assertEquals(canonical.y, result.y, EPS);
        assertEquals(canonical.z, result.z, EPS);
    }

    @Test
    @DisplayName("identity rotation → effectivePos = canonicalPos + (currentPivot − lockedPivot)")
    void computeEffectivePosition_identityRotation_addsRawDelta() {
        Vector3d canonical = new Vector3d(211.9, 76.77, -182.5);
        Vector3d lockedPivot = new Vector3d(-2.867e7, 127.8, 1.229e7);
        Vector3d currentPivot = new Vector3d(lockedPivot).add(-11.156, 0, 0);
        Quaterniond identity = new Quaterniond();

        Vector3d result = TrainTransformProvider.computeEffectivePosition(
            canonical, currentPivot, lockedPivot, identity);

        assertEquals(canonical.x - 11.156, result.x, 1e-6);
        assertEquals(canonical.y, result.y, EPS);
        assertEquals(canonical.z, result.z, EPS);
    }

    @Test
    @DisplayName("90° Y rotation maps pivot delta (1,0,0) → effectivePos delta (0,0,-1)")
    void computeEffectivePosition_yRotation_rotatesDelta() {
        Vector3d canonical = new Vector3d(0, 0, 0);
        Vector3d lockedPivot = new Vector3d(0, 0, 0);
        Vector3d currentPivot = new Vector3d(1, 0, 0);
        Quaterniond ninetyDegY = new Quaterniond().rotationY(Math.toRadians(90));

        Vector3d result = TrainTransformProvider.computeEffectivePosition(
            canonical, currentPivot, lockedPivot, ninetyDegY);

        // Rotating (1, 0, 0) by +90° around Y gives (0, 0, -1) in JOML's
        // right-handed frame. Confirms the compensation applies the locked
        // rotation to the pivot delta — important for trains spawned
        // facing non-+X directions if that support lands later.
        assertEquals(0.0, result.x, 1e-9);
        assertEquals(0.0, result.y, 1e-9);
        assertEquals(-1.0, result.z, 1e-9);
    }

    // ── World-load motion-grace elapsed-tick math ──────────────────────────
    // effectiveElapsedTicks is the pure core of the load-grace hold that
    // suppresses the join-time Sable "non-existent sub-level" burst: a
    // freshly-spawned carriage reports 0 elapsed ticks (holds at spawn) until
    // the dimension's grace deadline, then advances one tick at a time.

    /** Sentinel used for "this dimension was never granted a grace window". */
    private static final long NO_GRACE = Long.MIN_VALUE;

    @Test
    @DisplayName("during grace: carriage holds (0 elapsed) then starts smoothly at the deadline")
    void effectiveElapsedTicks_holdsThenStartsSmoothly() {
        long spawn = 1000L;      // seed/eager-fill carriages spawn at load tick
        long holdUntil = 1020L;  // spawn + WORLD_LOAD_MOTION_GRACE_TICKS

        // Every tick inside the window holds at the spawn position.
        assertEquals(0L, TrainTransformProvider.effectiveElapsedTicks(1000L, spawn, holdUntil));
        assertEquals(0L, TrainTransformProvider.effectiveElapsedTicks(1010L, spawn, holdUntil));
        assertEquals(0L, TrainTransformProvider.effectiveElapsedTicks(1019L, spawn, holdUntil));
        // At the deadline still 0; the very next tick is a single 1-tick step —
        // a smooth start, never a jump of the whole grace window.
        assertEquals(0L, TrainTransformProvider.effectiveElapsedTicks(1020L, spawn, holdUntil));
        assertEquals(1L, TrainTransformProvider.effectiveElapsedTicks(1021L, spawn, holdUntil));
        assertEquals(5L, TrainTransformProvider.effectiveElapsedTicks(1025L, spawn, holdUntil));
    }

    @Test
    @DisplayName("no grace window → normal elapsed = currentTick − spawnTick (no MIN_VALUE underflow)")
    void effectiveElapsedTicks_noGraceBehavesNormally() {
        long spawn = 1000L;
        // The sentinel must NOT underflow the subtraction (max() is taken
        // first), so behaviour is identical to the pre-grace formula.
        assertEquals(0L, TrainTransformProvider.effectiveElapsedTicks(1000L, spawn, NO_GRACE));
        assertEquals(7L, TrainTransformProvider.effectiveElapsedTicks(1007L, spawn, NO_GRACE));
        // Never negative even if a stale/future spawn tick is passed.
        assertEquals(0L, TrainTransformProvider.effectiveElapsedTicks(995L, spawn, NO_GRACE));
    }

    @Test
    @DisplayName("carriage appended after the grace deadline is unaffected (lockstep preserved)")
    void effectiveElapsedTicks_appendedAfterGraceIgnoresHold() {
        long holdUntil = 1020L;
        long spawn = 1050L; // appended during normal play, well past the window
        // holdUntil is in this carriage's past, so its own spawn tick is the
        // origin — exactly the pre-change behaviour, so it joins the moving
        // train in lockstep.
        assertEquals(0L, TrainTransformProvider.effectiveElapsedTicks(1050L, spawn, holdUntil));
        assertEquals(3L, TrainTransformProvider.effectiveElapsedTicks(1053L, spawn, holdUntil));
    }

    @Test
    @DisplayName("helper does not mutate its Vector3dc inputs")
    void computeEffectivePosition_doesNotMutateInputs() {
        Vector3d canonical = new Vector3d(10, 20, 30);
        Vector3d lockedPivot = new Vector3d(1, 2, 3);
        Vector3d currentPivot = new Vector3d(5, 2, 3);
        Quaterniond rotation = new Quaterniond();

        Vector3d canonicalBefore = new Vector3d(canonical);
        Vector3d lockedBefore = new Vector3d(lockedPivot);
        Vector3d currentBefore = new Vector3d(currentPivot);

        TrainTransformProvider.computeEffectivePosition(
            canonical, currentPivot, lockedPivot, rotation);

        assertEquals(canonicalBefore.x, canonical.x, EPS);
        assertEquals(canonicalBefore.y, canonical.y, EPS);
        assertEquals(canonicalBefore.z, canonical.z, EPS);
        assertEquals(lockedBefore.x, lockedPivot.x, EPS);
        assertEquals(lockedBefore.y, lockedPivot.y, EPS);
        assertEquals(lockedBefore.z, lockedPivot.z, EPS);
        assertEquals(currentBefore.x, currentPivot.x, EPS);
        assertEquals(currentBefore.y, currentPivot.y, EPS);
        assertEquals(currentBefore.z, currentPivot.z, EPS);
    }
}
