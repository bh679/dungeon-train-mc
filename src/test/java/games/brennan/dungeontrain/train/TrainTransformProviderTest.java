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

    // ── Frozen-tick elapsed math ───────────────────────────────────────────
    // The overload that stands a train still while somebody is inside one of its
    // portal rooms (TrainMotionFreeze). Frozen ticks are subtracted from the
    // elapsed count, which is the only place a position built as a pure function
    // of elapsed time can be stopped — and, because they were never counted,
    // resuming needs no code and produces no jump.

    @Test
    @DisplayName("frozen ticks hold the position, and it carries on from there — no jump either way")
    void frozenElapsedTicks_holdsThenResumesWithoutJumping() {
        long spawn = 1000L;

        // Running normally for 10 ticks.
        assertEquals(10L, TrainTransformProvider.effectiveElapsedTicks(1010L, spawn, NO_GRACE, 0L));

        // Frozen from 1010: every tick that passes is also a frozen tick, so the
        // elapsed count — and the position derived from it — stands still.
        assertEquals(10L, TrainTransformProvider.effectiveElapsedTicks(1011L, spawn, NO_GRACE, 1L));
        assertEquals(10L, TrainTransformProvider.effectiveElapsedTicks(1050L, spawn, NO_GRACE, 40L));

        // Released at 1050 with 40 ticks frozen. The next tick is a single step
        // from where it stopped, not a lurch covering the whole freeze.
        assertEquals(11L, TrainTransformProvider.effectiveElapsedTicks(1051L, spawn, NO_GRACE, 40L));
        assertEquals(15L, TrainTransformProvider.effectiveElapsedTicks(1055L, spawn, NO_GRACE, 40L));
    }

    @Test
    @DisplayName("a train that never freezes is identical to before the term existed")
    void frozenElapsedTicks_zeroIsTheOldBehaviour() {
        long spawn = 1000L;
        long holdUntil = 1020L;
        assertEquals(
            TrainTransformProvider.effectiveElapsedTicks(1030L, spawn, holdUntil),
            TrainTransformProvider.effectiveElapsedTicks(1030L, spawn, holdUntil, 0L));
        assertEquals(
            TrainTransformProvider.effectiveElapsedTicks(1007L, spawn, NO_GRACE),
            TrainTransformProvider.effectiveElapsedTicks(1007L, spawn, NO_GRACE, 0L));
    }

    @Test
    @DisplayName("a carriage appended after a freeze subtracts only what it was there for")
    void frozenElapsedTicks_countsOnlySinceSpawn() {
        // The train froze for 40 ticks before this carriage existed. Its provider
        // captured the running total at spawn, so it passes the DIFFERENCE — 0 —
        // and joins its siblings in lockstep. Passing the train's whole history
        // instead would place it 40 ticks of travel behind the group it couples to.
        long spawn = 2000L;
        assertEquals(5L, TrainTransformProvider.effectiveElapsedTicks(2005L, spawn, NO_GRACE, 0L));
        // And a later freeze it IS present for still stops it.
        assertEquals(5L, TrainTransformProvider.effectiveElapsedTicks(2010L, spawn, NO_GRACE, 5L));
    }

    @Test
    @DisplayName("the term can only stop a carriage, never reverse it")
    void frozenElapsedTicks_clampsAtZero() {
        long spawn = 1000L;
        // More frozen ticks than elapsed ones — only reachable if a counter ran
        // ahead of its baseline — must floor at the spawn position rather than
        // running the carriage backwards down the track.
        assertEquals(0L, TrainTransformProvider.effectiveElapsedTicks(1010L, spawn, NO_GRACE, 999L));
        // A negative difference is treated as none rather than added as travel.
        assertEquals(10L, TrainTransformProvider.effectiveElapsedTicks(1010L, spawn, NO_GRACE, -50L));
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

    @Test
    @DisplayName("shouldRebaseOnVelocityChange: only a real change of an already-set velocity re-bases")
    void shouldRebaseOnVelocityChange_truthTable() {
        Vector3d two = new Vector3d(2.0, 0.0, 0.0);
        assertFalse(TrainTransformProvider.shouldRebaseOnVelocityChange(null, two));            // first assignment
        assertFalse(TrainTransformProvider.shouldRebaseOnVelocityChange(two, new Vector3d(two))); // same value re-applied
        assertTrue(TrainTransformProvider.shouldRebaseOnVelocityChange(two, new Vector3d(0.0, 0.0, 0.0))); // speed 2 → 0
        assertTrue(TrainTransformProvider.shouldRebaseOnVelocityChange(new Vector3d(0.0, 0.0, 0.0), two)); // speed 0 → 2
    }

    // ──────────────────────────────────────────────────────────────────────
    // Velocity changes, on real providers. The change is recorded on the train
    // (TrainMotion) and each carriage replays it on its next tick — which is
    // what lets a carriage that was culled to Sable holding at the time come
    // back in formation. Every test has its own train id, so the static
    // schedule and frozen counters cannot leak between them.

    private static final Vector3d FOUR = new Vector3d(4, 0, 0);
    private static final Vector3d ZERO = new Vector3d(0, 0, 0);
    /** One group's stride on the reproduction train: 37 blocks of carriage plus the 0.4 seam. */
    private static final double STRIDE = 37.4;

    private static final ResourceKey<Level> OVERWORLD = ResourceKey.create(
        Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));

    private static TrainTransformProvider newProvider(UUID trainId, ResourceKey<Level> dim) {
        return new TrainTransformProvider(
            VEL, new BlockPos(0, 0, 0), dim, 0, 1, new CarriageDims(9, 7, 7), trainId);
    }

    /** As {@link #tick}, for a carriage whose spawn pose is at {@code spawnX}. */
    private static double tickAt(TrainTransformProvider p, double spawnX, long gameTime) {
        KinematicDriver.TickOutput out = p.nextTransform(new KinematicDriver.TickInput(
            new Vector3d(spawnX, 64, 0), new Quaterniond(), new Vector3d(2.0e7, 100, 2.0e7), gameTime));
        return out.position().x();
    }

    /** Tick every game tick in {@code [from, to]}; return the last emitted X. */
    private static double run(TrainTransformProvider p, double spawnX, long from, long to) {
        double x = Double.NaN;
        for (long t = from; t <= to; t++) x = tickAt(p, spawnX, t);
        return x;
    }

    @Test
    @DisplayName("mid-ride change: the pose is continuous, then advances at the new speed")
    void velocityChange_midRide_isContinuousThenUsesTheNewSpeed() {
        TrainTransformProvider p = newProvider();
        double at100 = run(p, 1000, 0, 100);
        assertEquals(1000.0 + 2.0 * 100 * DT, at100, EPS);

        p.setTargetVelocity(FOUR, 100L);
        // Not 1000 + 4 × 101 × DT (the whole ride re-priced, +10 blocks in one tick): one step.
        assertEquals(at100 + 4.0 * DT, tickAt(p, 1000, 101), EPS);
        double at150 = run(p, 1000, 102, 150);
        assertEquals(at100 + 4.0 * 50 * DT, at150, EPS);

        p.setTargetVelocity(ZERO, 150L);
        // Not back at the spawn X: parked where it is.
        assertEquals(at150, run(p, 1000, 151, 200), EPS);

        p.setTargetVelocity(VEL, 200L);
        assertEquals(at150 + 2.0 * 40 * DT, run(p, 1000, 201, 240), EPS);
    }

    @Test
    @DisplayName("held carriage: released after a speed change, it is where its sibling expects it")
    void velocityChange_whileHeld_carriageReturnsInFormation() {
        UUID trainId = UUID.randomUUID();
        TrainTransformProvider live = newProvider(trainId, OVERWORLD);
        TrainTransformProvider held = newProvider(trainId, OVERWORLD);
        run(live, 1000, 0, 50);
        run(held, 1000 + STRIDE, 0, 50);

        // `held` is culled to Sable holding: it stops being ticked, and nothing that walks the
        // loaded sub-levels can reach it. The speed changes while it is away.
        run(live, 1000, 51, 100);
        live.setTargetVelocity(FOUR, 100L);
        assertEquals(4.0, held.getTargetVelocity().x(), "a held carriage answers with the train's speed");
        double liveAt300 = run(live, 1000, 101, 300);

        // Released at tick 300. Left at the old speed it would be 20 blocks behind and losing 0.1
        // a tick — through the sibling behind it.
        double heldAt300 = tickAt(held, 1000 + STRIDE, 300);
        assertEquals(1000.0 + (2.0 * 100 + 4.0 * 200) * DT, liveAt300, EPS);
        assertEquals(STRIDE, heldAt300 - liveAt300, EPS);

        // ...and it stays there: both now run at the new speed.
        assertEquals(STRIDE, tickAt(held, 1000 + STRIDE, 301) - tickAt(live, 1000, 301), EPS);
        assertEquals(STRIDE, run(held, 1000 + STRIDE, 302, 400) - run(live, 1000, 302, 400), EPS);
    }

    @Test
    @DisplayName("held carriage: several changes while away are replayed in order")
    void velocityChange_severalWhileHeld_areAllReplayed() {
        UUID trainId = UUID.randomUUID();
        TrainTransformProvider live = newProvider(trainId, OVERWORLD);
        TrainTransformProvider held = newProvider(trainId, OVERWORLD);
        run(live, 1000, 0, 50);
        run(held, 1000 + STRIDE, 0, 50);

        run(live, 1000, 51, 100);
        live.setTargetVelocity(FOUR, 100L);
        run(live, 1000, 101, 160);
        live.setTargetVelocity(ZERO, 160L);
        run(live, 1000, 161, 220);
        live.setTargetVelocity(new Vector3d(6, 0, 0), 220L);
        double liveAt300 = run(live, 1000, 221, 300);

        assertEquals(1000.0 + (2.0 * 100 + 4.0 * 60 + 0.0 * 60 + 6.0 * 80) * DT, liveAt300, EPS);
        assertEquals(STRIDE, tickAt(held, 1000 + STRIDE, 300) - liveAt300, EPS);
        assertEquals(STRIDE, tickAt(held, 1000 + STRIDE, 301) - tickAt(live, 1000, 301), EPS);
    }

    @Test
    @DisplayName("a carriage not ticked since before the change re-bases to the change, not to its last tick")
    void velocityChange_afterATickGap_pricesTheGapAtTheOldSpeed() {
        // The reproduction's second fault: loaded when the command ran, so the change reached it,
        // but last ticked 15 ticks before and next ticked 67 after. Re-basing onto its last
        // emitted position left it 14.9 blocks behind its neighbours for good.
        TrainTransformProvider p = newProvider();
        run(p, 1000, 0, 18);
        p.setTargetVelocity(FOUR, 33L);
        assertEquals(1000.0 + (2.0 * 33 + 4.0 * 67) * DT, tickAt(p, 1000, 100), EPS);
    }

    @Test
    @DisplayName("a carriage appended after a change starts on the train's current speed")
    void velocityChange_carriageAppendedAfterwards_usesTheCurrentSpeed() {
        UUID trainId = UUID.randomUUID();
        TrainTransformProvider first = newProvider(trainId, OVERWORLD);
        run(first, 1000, 0, 100);
        first.setTargetVelocity(FOUR, 100L);
        run(first, 1000, 101, 120);

        // Built with the velocity its caller read before the change (VEL) — the constructor takes
        // the train's — and with nothing to replay: no ticks before its own spawn to re-price.
        TrainTransformProvider appended = newProvider(trainId, OVERWORLD);
        appended.preSeedSpawnTick(120L);
        assertEquals(4.0, appended.getTargetVelocity().x());
        assertEquals(2000.0, tickAt(appended, 2000, 120), EPS);
        assertEquals(2000.0 + 4.0 * DT, tickAt(appended, 2000, 121), EPS);
    }

    @Test
    @DisplayName("re-applying the current speed is not a change")
    void velocityChange_sameValue_leavesTheScheduleAlone() {
        TrainTransformProvider p = newProvider();
        run(p, 1000, 0, 50);
        p.setTargetVelocity(new Vector3d(VEL), 50L);
        assertEquals(0, TrainMotion.epoch(p.getTrainId()));
        assertEquals(1000.0 + 2.0 * 60 * DT, run(p, 1000, 51, 60), EPS);
    }

    @Test
    @DisplayName("a change while the train is frozen: it stays put, then leaves at the new speed")
    void velocityChange_whileFrozen_holdsThenResumesAtTheNewSpeed() {
        UUID trainId = UUID.randomUUID();
        TrainTransformProvider live = newProvider(trainId, OVERWORLD);
        TrainTransformProvider held = newProvider(trainId, OVERWORLD);
        double parked = run(live, 1000, 0, 100);
        run(held, 1000 + STRIDE, 0, 50);

        TrainMotionFreeze.setFrozen(trainId, true);
        try {
            for (long t = 101; t <= 120; t++) {
                TrainMotionFreeze.tickFrozen();
                assertEquals(parked, tickAt(live, 1000, t), EPS, "frozen at tick " + t);
                if (t == 110) live.setTargetVelocity(FOUR, t);
            }
        } finally {
            TrainMotionFreeze.setFrozen(trainId, false);
        }

        assertEquals(parked + 4.0 * DT, tickAt(live, 1000, 121), EPS);
        double liveAt130 = run(live, 1000, 122, 130);
        assertEquals(parked + 4.0 * 10 * DT, liveAt130, EPS);
        // The carriage that was away for the freeze and the change subtracts the same stopped time.
        assertEquals(STRIDE, tickAt(held, 1000 + STRIDE, 130) - liveAt130, EPS);
    }

    @Test
    @DisplayName("a change inside the world-load hold: still held, then starts at the new speed")
    void velocityChange_duringLoadGrace_startsAtTheNewSpeed() {
        // Its own dimension: the hold deadline is per dimension and static.
        ResourceKey<Level> dim = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("dungeontrain", "test_load_grace"));
        TrainTransformProvider.beginLoadGrace(dim, 0L); // holds until tick 20
        TrainTransformProvider p = newProvider(UUID.randomUUID(), dim);

        assertEquals(1000.0, run(p, 1000, 0, 10), EPS);
        p.setTargetVelocity(FOUR, 10L);
        assertEquals(1000.0, run(p, 1000, 11, 20), EPS);
        assertEquals(1000.0 + 4.0 * DT, tickAt(p, 1000, 21), EPS);
        assertEquals(1000.0 + 4.0 * 10 * DT, run(p, 1000, 22, 30), EPS);
    }

    @Test
    @DisplayName("a train whose speed never changes computes exactly what it always did")
    void noVelocityChange_isBitIdenticalToTheFormula() {
        TrainTransformProvider p = newProvider();
        for (long t = 0; t <= 2000; t++) {
            // No tolerance: the same expression, in the same order, as before the schedule existed.
            assertEquals(1000.0 + 2.0 * t * DT, tickAt(p, 1000, t), "tick " + t);
        }
        assertEquals(0, TrainMotion.epoch(p.getTrainId()));
    }
}
