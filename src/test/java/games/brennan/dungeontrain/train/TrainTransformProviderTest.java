package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.ship.KinematicDriver;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ──────────────────────────────────────────────────────────────────────
    // Resume-after-cull re-anchor (Part 2 of the jitter fix). When a carriage
    // sub-level is culled to Sable holding it stops being ticked; on reload the
    // deterministic formula catches canonicalPos up to its correct (sibling-
    // aligned) position in a single frame. The re-anchor re-bases the spawn
    // baseline onto that already-correct position so future ticks stay smooth,
    // WITHOUT changing any emitted absolute position (zero drift).

    /** Constant world velocity of +2 blocks/s along X (0.1 block/tick at 20 Hz). */
    private static final Vector3d VEL = new Vector3d(2, 0, 0);
    private static final double DT = 1.0 / 20.0;

    private static TrainTransformProvider newProvider() {
        ResourceKey<Level> dim = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));
        return new TrainTransformProvider(
            VEL, new BlockPos(0, 0, 0), dim, 0, 1,
            new CarriageDims(9, 7, 7), UUID.randomUUID());
    }

    /** Drive one physics tick at {@code gameTime} from a stable spawn pose; return emitted X. */
    private static double tick(TrainTransformProvider p, long gameTime) {
        Vector3d spawnPose = new Vector3d(1000, 64, 0);
        Vector3d pivot = new Vector3d(2.0e7, 100, 2.0e7); // shipyard-space pivot (stable)
        KinematicDriver.TickOutput out = p.nextTransform(
            new KinematicDriver.TickInput(spawnPose, new Quaterniond(), pivot, gameTime));
        return out.position().x();
    }

    @Test
    @DisplayName("gap-resume: emitted position equals pure extrapolation (zero drift)")
    void nextTransform_gapResume_emitsSameAbsolutePositionAsNonRebased() {
        TrainTransformProvider p = newProvider();
        tick(p, 0);   // baseline captured: spawnWorldPos.x = 1000, spawnGameTick = 0
        tick(p, 1);
        tick(p, 2);
        // Simulate a cull: ticks 3..99 never fired. Reload at tick 100.
        double atGap = tick(p, 100);
        // Correct catch-up = spawnWorldPos0 + velocity * 100 * DT = 1000 + 2*100*0.05 = 1010.
        assertEquals(1000.0 + 2.0 * 100 * DT, atGap, 1e-6);
    }

    @Test
    @DisplayName("gap-resume: after re-anchor the next contiguous tick advances by exactly velocity*dt")
    void nextTransform_afterGapRebase_nextTickAdvancesByOneStep() {
        TrainTransformProvider p = newProvider();
        tick(p, 0);
        tick(p, 1);
        double atGap = tick(p, 100);   // re-anchor: spawnWorldPos.x := 1010, spawnGameTick := 100
        double next = tick(p, 101);    // contiguous — one step from the re-based anchor
        assertEquals(atGap + 2.0 * DT, next, 1e-6);
    }

    @Test
    @DisplayName("no gap: contiguous ticks extrapolate identically to a never-culled driver")
    void nextTransform_noGap_matchesContinuousExtrapolation() {
        TrainTransformProvider p = newProvider();
        tick(p, 0);
        double t50 = 0;
        for (long t = 1; t <= 50; t++) t50 = tick(p, t);
        // 50 contiguous ticks: 1000 + 2*50*0.05 = 1005. Re-anchor must never fire here.
        assertEquals(1000.0 + 2.0 * 50 * DT, t50, 1e-6);
    }

    @Test
    @DisplayName("shouldReanchor: true only when the tick gap exceeds one and a baseline exists")
    void shouldReanchor_truthTable() {
        assertFalse(TrainTransformProvider.shouldReanchor(-1L, 100L)); // no baseline yet
        assertFalse(TrainTransformProvider.shouldReanchor(10L, 11L));  // contiguous (gap 1)
        assertFalse(TrainTransformProvider.shouldReanchor(10L, 10L));  // same tick (gap 0)
        assertTrue(TrainTransformProvider.shouldReanchor(10L, 12L));   // gap 2
        assertTrue(TrainTransformProvider.shouldReanchor(10L, 1310L)); // large cull gap
    }
}
