package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.train.TrainCarriageAppender.TrailingId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TrainCarriageAppender#computeGroupAnchorsToSpawn} —
 * the pure decision helper that decides which group anchors to spawn this
 * tick. The helper is JOML- / Forge-free so tests run without a Minecraft
 * bootstrap.
 *
 * <p>With the per-group architecture, a "train" is a collection of Sable
 * sub-levels each holding {@code groupSize} consecutive carriages. The
 * helper takes the train's current min/max group anchors and the resolved
 * needed pIdx range (already unioned across all near players, with each
 * player's halfBack/halfFront applied by the caller) and decides which
 * NEW anchors need to be spawned to cover that range.</p>
 *
 * <p>Default fixture: {@code count=10} → {@code halfBack=4}, {@code halfFront=5}.
 * Tests apply these per-player half-widths to compute the {@code maxNeededPIdx} /
 * {@code minNeededPIdx} arguments before calling the helper, mirroring what
 * the per-player loop in {@link TrainCarriageAppender#updateTrain} does.</p>
 */
final class TrainCarriageAppenderTest {

    private static final int HALF_BACK = 4;
    private static final int HALF_FRONT = 5;
    private static final int GROUP_SIZE = 3;
    /** Lead anchor for the default bootstrap with groupSize=3 and count=10. */
    private static final int INITIAL_MAX_ANCHOR = 3;
    /** Tail anchor for the default bootstrap. */
    private static final int INITIAL_MIN_ANCHOR = -6;

    /** Helper: max needed pIdx across players, mirroring the appender's per-player loop. */
    private static int maxNeeded(int... playerPIdxs) {
        int m = Integer.MIN_VALUE;
        for (int p : playerPIdxs) m = Math.max(m, p + HALF_FRONT);
        return m;
    }

    /** Helper: min needed pIdx across players, mirroring the appender's per-player loop. */
    private static int minNeeded(int... playerPIdxs) {
        int m = Integer.MAX_VALUE;
        for (int p : playerPIdxs) m = Math.min(m, p - HALF_BACK);
        return m;
    }

    @Test
    @DisplayName("no near players → empty list")
    void noPlayers_returnsEmpty() {
        List<Integer> out = TrainCarriageAppender.computeGroupAnchorsToSpawn(
            INITIAL_MAX_ANCHOR, INITIAL_MIN_ANCHOR, Integer.MIN_VALUE, Integer.MAX_VALUE, GROUP_SIZE);
        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("groupSize=0 throws (defensive)")
    void groupSizeZero_throws() {
        assertThrows(IllegalArgumentException.class, () ->
            TrainCarriageAppender.computeGroupAnchorsToSpawn(0, 0, 5, -5, 0));
    }

    @Test
    @DisplayName("player still inside initial range → no spawn")
    void playerInsideRange_returnsEmpty() {
        // Player at pIdx=0; needs [-4, 5]. Train covers [-6, 5]. Already covered.
        List<Integer> out = TrainCarriageAppender.computeGroupAnchorsToSpawn(
            INITIAL_MAX_ANCHOR, INITIAL_MIN_ANCHOR, maxNeeded(0), minNeeded(0), GROUP_SIZE);
        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("walk one carriage past lead group → spawn one new group ahead")
    void walkForwardCrossesGroupBoundary_spawnsOneGroup() {
        // Player at pIdx=1; needs [-3, 6]. Train covers [-6, 5]. needHigh=6 →
        // floorDiv(6, 3) = 2 * 3 = 6. trainMax=3. Forward: anchor 6.
        List<Integer> out = TrainCarriageAppender.computeGroupAnchorsToSpawn(
            INITIAL_MAX_ANCHOR, INITIAL_MIN_ANCHOR, maxNeeded(1), minNeeded(1), GROUP_SIZE);
        assertEquals(List.of(6), out);
    }

    @Test
    @DisplayName("walk one carriage past tail group → spawn one new group behind")
    void walkBackwardCrossesGroupBoundary_spawnsOneGroup() {
        // Player at pIdx=-3; needs [-7, 2]. Train covers [-6, 5]. needLow=-7 →
        // floorDiv(-7, 3) = -3 * 3 = -9. trainMin=-6. Backward: anchor -9.
        List<Integer> out = TrainCarriageAppender.computeGroupAnchorsToSpawn(
            INITIAL_MAX_ANCHOR, INITIAL_MIN_ANCHOR, maxNeeded(-3), minNeeded(-3), GROUP_SIZE);
        assertEquals(List.of(-9), out);
    }

    @Test
    @DisplayName("player jumps forward by 10 carriages → spawns multiple groups ascending")
    void jumpForward_spawnsMultipleGroupsAscending() {
        // Player at pIdx=10; needs [6, 15]. floorDiv(15, 3) = 5 * 3 = 15.
        // From trainMax=3, forward anchors: 6, 9, 12, 15.
        List<Integer> out = TrainCarriageAppender.computeGroupAnchorsToSpawn(
            INITIAL_MAX_ANCHOR, INITIAL_MIN_ANCHOR, maxNeeded(10), minNeeded(10), GROUP_SIZE);
        assertEquals(List.of(6, 9, 12, 15), out);
    }

    @Test
    @DisplayName("player jumps backward by 10 → spawns multiple groups descending")
    void jumpBackward_spawnsMultipleGroupsDescending() {
        // Player at pIdx=-10; needs [-14, -5]. floorDiv(-14, 3) = -5 * 3 = -15.
        // From trainMin=-6, backward anchors: -9, -12, -15.
        List<Integer> out = TrainCarriageAppender.computeGroupAnchorsToSpawn(
            INITIAL_MAX_ANCHOR, INITIAL_MIN_ANCHOR, maxNeeded(-10), minNeeded(-10), GROUP_SIZE);
        assertEquals(List.of(-9, -12, -15), out);
    }

    @Test
    @DisplayName("two players forward+backward → spawn both frontiers (forward first, then backward)")
    void twoPlayersBothFrontiers_spawnsBothInOrder() {
        // p1 at pIdx=3 needs [-1, 8] → forward floorDiv(8,3)*3 = 6.
        // p2 at pIdx=-3 needs [-7, 2] → backward floorDiv(-7,3)*3 = -9.
        // Forward: anchor 6. Backward: anchor -9. Output: 6 then -9.
        List<Integer> out = TrainCarriageAppender.computeGroupAnchorsToSpawn(
            INITIAL_MAX_ANCHOR, INITIAL_MIN_ANCHOR, maxNeeded(3, -3), minNeeded(3, -3), GROUP_SIZE);
        assertEquals(List.of(6, -9), out);
    }

    @Test
    @DisplayName("multiple players, only outermost forward matters")
    void multipleForward_outermostWins() {
        // p1 at pIdx=2 needs [-2, 7] → forward anchor 6 (floorDiv(7,3)*3).
        // p2 at pIdx=12 needs [8, 17] → forward anchor 15 (floorDiv(17,3)*3=15).
        // Both contribute backward needs: min(-2, 8) = -2, floorDiv(-2,3)*3 = -3.
        // -3 ≥ trainMin -6, so no backward spawn.
        // Forward: 6, 9, 12, 15.
        List<Integer> out = TrainCarriageAppender.computeGroupAnchorsToSpawn(
            INITIAL_MAX_ANCHOR, INITIAL_MIN_ANCHOR, maxNeeded(2, 12), minNeeded(2, 12), GROUP_SIZE);
        assertEquals(List.of(6, 9, 12, 15), out);
    }

    @Test
    @DisplayName("train already covers needed range → no spawn (monotonicity)")
    void trainAlreadyCoversNeed_noSpawn() {
        // Pretend the train already extends from anchor -99 to 99 (huge train).
        // Player at pIdx=0 needs [-4, 5] — well inside.
        List<Integer> out = TrainCarriageAppender.computeGroupAnchorsToSpawn(
            99, -99, maxNeeded(0), minNeeded(0), GROUP_SIZE);
        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("groupSize=1 reduces to per-carriage append (B.1 parity)")
    void groupSizeOne_perCarriageBehavior() {
        // With groupSize=1, the helper should produce one anchor per
        // carriage to spawn, matching the B.1 single-carriage architecture.
        // Player at pIdx=10 with trainMax=5 (B.1 fixture); needs [6, 15].
        // Forward anchors: 6, 7, 8, 9, 10, 11, 12, 13, 14, 15.
        List<Integer> out = TrainCarriageAppender.computeGroupAnchorsToSpawn(
            5, -4, maxNeeded(10), minNeeded(10), 1);
        assertEquals(List.of(6, 7, 8, 9, 10, 11, 12, 13, 14, 15), out);
    }

    @Test
    @DisplayName("large groupSize (16) snaps far outward")
    void largeGroupSize_snapsAggressively() {
        // groupSize=16. count=10 fixture: halfBack=4, halfFront=5.
        // Player at pIdx=20 needs [16, 25]. floorDiv(25, 16) = 1 * 16 = 16.
        // Train min/max anchors at 0 (assume groupSize=16 bootstrap covers [0, 15]).
        // Forward: anchor 16.
        List<Integer> out = TrainCarriageAppender.computeGroupAnchorsToSpawn(
            0, 0, maxNeeded(20), minNeeded(20), 16);
        assertEquals(List.of(16), out);
    }

    @Test
    @DisplayName("backward across negative pIdx with groupSize=3 — floorDiv handles negatives")
    void negativePIdxFloorDiv_correctAnchors() {
        // Player at pIdx=-100 needs [-104, -95]. floorDiv(-104, 3) = -35 * 3 = -105.
        // From trainMin=-6, backward step by -3: -9, -12, ..., -105.
        // That's (105 - 6) / 3 = 33 anchors.
        List<Integer> out = TrainCarriageAppender.computeGroupAnchorsToSpawn(
            INITIAL_MAX_ANCHOR, INITIAL_MIN_ANCHOR, maxNeeded(-100), minNeeded(-100), GROUP_SIZE);
        assertEquals(33, out.size());
        assertEquals(-9, (int) out.get(0));
        assertEquals(-105, (int) out.get(out.size() - 1));
    }

    // ---- Trailing force-load window target selector ----
    //
    // backmostForceLoadTargets is the policy core of the
    // backward-generation-stall fix: it picks the carriages nearest the tail
    // (lowest pIdx) to force-load so Sable can't cull them mid-settle. The
    // reconcile that consumes this set is a plain add/release set-diff over the
    // live tickets, exercised end-to-end by the in-game Gate 2 test.

    /** Deterministic sub-level id for carriage {@code n} (stable assertions). */
    private static UUID slId(int n) {
        return new UUID(0L, n);
    }

    /** Carriages at the given pIdxs, each with slId(pIdx) so ids track pIdx. */
    private static List<TrailingId> trailing(int... pidxs) {
        List<TrailingId> out = new ArrayList<>();
        for (int p : pidxs) out.add(new TrailingId(p, slId(p)));
        return out;
    }

    @Test
    @DisplayName("force-load window selects the backmost (lowest-pIdx) N carriages")
    void forceLoad_picksBackmostN() {
        // Tail .. front: -5 -4 -3 -2 -1 0 1 2. Backmost 3 = {-5,-4,-3}.
        Set<UUID> target = TrainCarriageAppender.backmostForceLoadTargets(
            trailing(2, 1, 0, -1, -2, -3, -4, -5), 3);
        assertEquals(Set.of(slId(-5), slId(-4), slId(-3)), target);
    }

    @Test
    @DisplayName("force-load window returns all carriages when the train is shorter than the window")
    void forceLoad_shorterThanWindowReturnsAll() {
        Set<UUID> target = TrainCarriageAppender.backmostForceLoadTargets(trailing(0, -1), 6);
        assertEquals(Set.of(slId(0), slId(-1)), target);
    }

    @Test
    @DisplayName("force-load window: maxCarriages <= 0 or empty input yields empty (inactive)")
    void forceLoad_emptyOrZeroYieldsEmpty() {
        assertTrue(TrainCarriageAppender.backmostForceLoadTargets(trailing(0, -1, -2), 0).isEmpty());
        assertTrue(TrainCarriageAppender.backmostForceLoadTargets(trailing(0, -1, -2), -1).isEmpty());
        assertTrue(TrainCarriageAppender.backmostForceLoadTargets(List.of(), 3).isEmpty());
    }

    @Test
    @DisplayName("force-load window selection is independent of input ordering")
    void forceLoad_orderIndependent() {
        Set<UUID> a = TrainCarriageAppender.backmostForceLoadTargets(trailing(-2, 0, -1, 1, -3), 2);
        Set<UUID> b = TrainCarriageAppender.backmostForceLoadTargets(trailing(1, -3, 0, -1, -2), 2);
        assertEquals(Set.of(slId(-3), slId(-2)), a);
        assertEquals(a, b);
    }

    @Test
    @DisplayName("force-load window breaks pIdx ties deterministically (stable window edge)")
    void forceLoad_tieBreakDeterministic() {
        UUID a = new UUID(0L, 100);
        UUID b = new UUID(0L, 200);
        List<TrailingId> train = new ArrayList<>(List.of(
            new TrailingId(-3, a), new TrailingId(-3, b), new TrailingId(0, slId(0))));
        Set<UUID> first = TrainCarriageAppender.backmostForceLoadTargets(train, 2);
        Set<UUID> second = TrainCarriageAppender.backmostForceLoadTargets(train, 2);
        assertEquals(first, second);
        assertEquals(Set.of(a, b), first); // both tied backmost entries fit a window of 2
    }

    @Test
    @DisplayName("force-load window of 1 holds only the single backmost carriage")
    void forceLoad_windowOfOne() {
        Set<UUID> target = TrainCarriageAppender.backmostForceLoadTargets(trailing(3, -7, 2, -1), 1);
        assertEquals(Set.of(slId(-7)), target);
    }

    // ---- Option 2: registry-edge reference resolution ----
    //
    // decideEdgeAction is the pure decision core: given whether the registry
    // edge sub-level is visible / held / a live registry wrapper, choose
    // SPAWN (place against it), RELOAD_DEFER (reload from holding, defer), or
    // DEFER (not yet surfaced). subLevelDeltaFor proves the placement invariant
    // that makes the void impossible: delta is always ±1 because the reference
    // IS the registry edge.

    @Test
    @DisplayName("decideEdgeAction: visible-and-live → SPAWN")
    void decide_visible_spawns() {
        assertEquals(TrainCarriageAppender.EdgeAction.SPAWN,
            TrainCarriageAppender.decideEdgeAction(true, false, false));
        // Visible always wins regardless of the other flags.
        assertEquals(TrainCarriageAppender.EdgeAction.SPAWN,
            TrainCarriageAppender.decideEdgeAction(true, true, true));
    }

    @Test
    @DisplayName("decideEdgeAction: culled-to-holding → RELOAD_DEFER")
    void decide_held_reloadDefers() {
        assertEquals(TrainCarriageAppender.EdgeAction.RELOAD_DEFER,
            TrainCarriageAppender.decideEdgeAction(false, true, false));
    }

    @Test
    @DisplayName("decideEdgeAction: live registry wrapper (transient findAll dropout) → SPAWN")
    void decide_registryResident_spawns() {
        assertEquals(TrainCarriageAppender.EdgeAction.SPAWN,
            TrainCarriageAppender.decideEdgeAction(false, false, true));
    }

    @Test
    @DisplayName("decideEdgeAction: not visible, not held, no live AABB → DEFER")
    void decide_absent_defers() {
        assertEquals(TrainCarriageAppender.EdgeAction.DEFER,
            TrainCarriageAppender.decideEdgeAction(false, false, false));
    }

    @Test
    @DisplayName("decideEdgeAction: held takes precedence over a stale registry AABB (no void)")
    void decide_heldBeatsStaleRegistryAabb() {
        // A held edge's registry wrapper can still report a non-zero (stale)
        // AABB. held MUST win so we reload rather than place against a stale pose.
        assertEquals(TrainCarriageAppender.EdgeAction.RELOAD_DEFER,
            TrainCarriageAppender.decideEdgeAction(false, true, true));
    }

    @Test
    @DisplayName("subLevelDeltaFor: backward spawn against the registry edge is always -1")
    void subLevelDelta_backwardIsMinusOne() {
        for (int groupSize : new int[] { 1, 3, 16 }) {
            for (int edge : new int[] { 0, -6, 7, -105 }) {
                int newAnchor = edge - groupSize; // backwardAnchor
                assertEquals(-1, TrainCarriageAppender.subLevelDeltaFor(newAnchor, edge, groupSize),
                    "groupSize=" + groupSize + " edge=" + edge);
            }
        }
    }

    @Test
    @DisplayName("subLevelDeltaFor: forward spawn against the registry edge is always +1")
    void subLevelDelta_forwardIsPlusOne() {
        for (int groupSize : new int[] { 1, 3, 16 }) {
            for (int edge : new int[] { 0, -6, 7, 105 }) {
                int newAnchor = edge + groupSize; // forwardAnchor
                assertEquals(1, TrainCarriageAppender.subLevelDeltaFor(newAnchor, edge, groupSize),
                    "groupSize=" + groupSize + " edge=" + edge);
            }
        }
    }

    @Test
    @DisplayName("subLevelDeltaFor: groupSize=0 throws (defensive)")
    void subLevelDelta_groupSizeZeroThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> TrainCarriageAppender.subLevelDeltaFor(-3, 0, 0));
    }

    // ---- Walk-away release recoverability guard (pause/resume regen fix) ----
    //
    // shouldRetainOnWalkAway is the pure decision core for releaseTrainForceLoads:
    // when the player leaves the train's vicinity, an un-serialized group (no on-disk
    // pointer) MUST stay force-loaded. Culling it before its first serialization
    // yields a null-pointer holding entry snatchAndLoad can't revive, so the carriage
    // respawns FRESH (re-rolled, edits lost) instead of reloading. A singleplayer
    // pause/resume transiently flings the rider off (Sable carry lag), tripping the
    // walk-away bail; without this guard it stripped the un-serialized frontier groups
    // and the whole train regenerated. Mirrors the guard reconcileForceLoads applies.

    @Test
    @DisplayName("shouldRetainOnWalkAway: un-serialized (no pointer) → retain (held)")
    void retain_unserialized_isHeld() {
        assertTrue(TrainCarriageAppender.shouldRetainOnWalkAway(false));
    }

    @Test
    @DisplayName("shouldRetainOnWalkAway: serialized (has pointer) → releasable")
    void retain_serialized_releasable() {
        assertFalse(TrainCarriageAppender.shouldRetainOnWalkAway(true));
    }

    // ---- Spawn-time hold policy (forward-carriage vanish fix) ----
    //
    // shouldHoldSpawnedGroup is the pure decision core routed by BOTH auto-spawn lanes
    // (forward + backward). A freshly-spawned group must be held resident until it serializes,
    // so Sable's per-tick simulation-distance cull can't drop an un-serialized forward group
    // into a null-pointer holding entry that reloadFromHolding can't revive (the "train vanishes"
    // report). This test locks the lanes symmetric so a future change can't silently re-introduce
    // the forward-only-unheld regression. The live ticket→serialize→drain cycle stays a Gate 2
    // in-game probe (the suite has no Sable bootstrap).

    @Test
    @DisplayName("shouldHoldSpawnedGroup: both auto-spawn lanes hold new groups (forward/backward symmetric)")
    void holdSpawnedGroup_symmetricAcrossLanes() {
        assertTrue(TrainCarriageAppender.shouldHoldSpawnedGroup(true));   // forward lane
        assertTrue(TrainCarriageAppender.shouldHoldSpawnedGroup(false));  // backward lane
    }

    // ---- Resume-grace window state machine (pause/resume regen fix) ----
    //
    // withinResumeGrace + shouldRenewResumeGrace are the pure cores behind the updateTrain
    // walk-away bail: on a singleplayer resume, ResumeWatchdog grants a grace deadline; the
    // bail holds the force-loads (and the whole-train resume-hold) while the window is open,
    // renewing it each tick the rider is still not near — but only until a hard cap measured
    // from the resume start, so a genuine post-resume walk-away eventually releases.

    @Test
    @DisplayName("withinResumeGrace: no deadline (normal walk-away) → not held")
    void grace_noDeadline_notHeld() {
        assertFalse(TrainCarriageAppender.withinResumeGrace(null, 100L));
    }

    @Test
    @DisplayName("withinResumeGrace: open through the deadline tick (inclusive), closed after")
    void grace_inclusiveDeadline() {
        assertTrue(TrainCarriageAppender.withinResumeGrace(150L, 100L));  // before
        assertTrue(TrainCarriageAppender.withinResumeGrace(150L, 150L));  // exactly at deadline
        assertFalse(TrainCarriageAppender.withinResumeGrace(150L, 151L)); // past deadline
    }

    @Test
    @DisplayName("shouldRenewResumeGrace: renews within the cap, stops at/after it")
    void grace_renewUntilCap() {
        int cap = 200;
        assertFalse(TrainCarriageAppender.shouldRenewResumeGrace(null, 50L, cap)); // no recovery
        assertTrue(TrainCarriageAppender.shouldRenewResumeGrace(0L, 199L, cap));   // within cap
        assertFalse(TrainCarriageAppender.shouldRenewResumeGrace(0L, 200L, cap));  // exactly at cap
        assertFalse(TrainCarriageAppender.shouldRenewResumeGrace(0L, 260L, cap));  // past cap → release
    }

    // ---- Reload-from-holding throttle (Sable snatch-miss log-spam fix) ----
    //
    // claimReloadIssue gates SableShipyard.reloadFromHolding to one call per
    // held-edge episode. A held edge sits in RELOAD_DEFER for the whole ~200-tick
    // surfacing window; the reload is a no-op there (entry present in Sable's global
    // holding map yet absent from the chunk's, so snatchAndLoad snatches nothing),
    // and each call re-triggers Sable's benign "wasn't present in the holding chunk"
    // ERROR 1:1. The helper returns true only the first time per (trainId, direction,
    // subLevelId) and re-arms when the held edge changes. Each test uses a distinct
    // random trainId so the shared static latch maps stay isolated. The clear-on-
    // resolve and per-tick prune wiring is exercised end-to-end by the Gate 2 ride.

    @Test
    @DisplayName("claimReloadIssue: true once per held-edge episode, false on repeat")
    void claimReload_oncePerEpisode() {
        UUID train = UUID.randomUUID();
        UUID edge = new UUID(1L, 1L);
        assertTrue(TrainCarriageAppender.claimReloadIssue(train, true, edge));   // first tick issues
        assertFalse(TrainCarriageAppender.claimReloadIssue(train, true, edge));  // same edge next tick
        assertFalse(TrainCarriageAppender.claimReloadIssue(train, true, edge));  // still throttled
    }

    @Test
    @DisplayName("claimReloadIssue: a new sub-level (edge changed) re-arms the throttle")
    void claimReload_newEdgeRearms() {
        UUID train = UUID.randomUUID();
        UUID edgeA = new UUID(2L, 1L);
        UUID edgeB = new UUID(2L, 2L);
        assertTrue(TrainCarriageAppender.claimReloadIssue(train, true, edgeA));
        assertFalse(TrainCarriageAppender.claimReloadIssue(train, true, edgeA));
        assertTrue(TrainCarriageAppender.claimReloadIssue(train, true, edgeB));  // new episode → issue once
        assertFalse(TrainCarriageAppender.claimReloadIssue(train, true, edgeB));
        // edgeA again is now a fresh episode (the stored id is B).
        assertTrue(TrainCarriageAppender.claimReloadIssue(train, true, edgeA));
    }

    @Test
    @DisplayName("claimReloadIssue: forward and backward latches are independent")
    void claimReload_directionIndependent() {
        UUID train = UUID.randomUUID();
        UUID edge = new UUID(3L, 1L);
        assertTrue(TrainCarriageAppender.claimReloadIssue(train, true, edge));   // forward issues
        assertTrue(TrainCarriageAppender.claimReloadIssue(train, false, edge));  // backward issues (separate map)
        assertFalse(TrainCarriageAppender.claimReloadIssue(train, true, edge));  // forward now throttled
        assertFalse(TrainCarriageAppender.claimReloadIssue(train, false, edge)); // backward now throttled
    }

    @Test
    @DisplayName("claimReloadIssue: different trains don't share throttle state")
    void claimReload_perTrain() {
        UUID trainA = UUID.randomUUID();
        UUID trainB = UUID.randomUUID();
        UUID edge = new UUID(4L, 1L);
        assertTrue(TrainCarriageAppender.claimReloadIssue(trainA, true, edge));
        assertTrue(TrainCarriageAppender.claimReloadIssue(trainB, true, edge));  // independent train → issues
        assertFalse(TrainCarriageAppender.claimReloadIssue(trainA, true, edge));
        assertFalse(TrainCarriageAppender.claimReloadIssue(trainB, true, edge));
    }

    // shouldHoldGroupNearPlayer is the pure decision core for the near-player
    // resident WINDOW hold (Part 1 of the jitter fix): pin a loaded group iff a
    // player is near AND the group's carriage range [anchor, highest] overlaps
    // the render-distance-bounded near window [nearMin, nearMax]. Window, not a
    // cap — it slides with the player and never dumps the near set all at once.

    @Test
    @DisplayName("shouldHoldGroupNearPlayer: group inside / overlapping the near window → hold")
    void holdGroup_overlap() {
        // group [6,8] fully inside window [0,20]
        assertTrue(TrainCarriageAppender.shouldHoldGroupNearPlayer(true, 6, 8, 0, 20));
        // group [0,2] overlaps window low edge [2,10]
        assertTrue(TrainCarriageAppender.shouldHoldGroupNearPlayer(true, 0, 2, 2, 10));
        // group [10,12] overlaps window high edge [0,10]
        assertTrue(TrainCarriageAppender.shouldHoldGroupNearPlayer(true, 10, 12, 0, 10));
    }

    @Test
    @DisplayName("shouldHoldGroupNearPlayer: group entirely outside the near window → no hold")
    void holdGroup_noOverlap() {
        // group [0,2] entirely below window [10,20]
        assertFalse(TrainCarriageAppender.shouldHoldGroupNearPlayer(true, 0, 2, 10, 20));
        // group [30,32] entirely above window [0,20]
        assertFalse(TrainCarriageAppender.shouldHoldGroupNearPlayer(true, 30, 32, 0, 20));
    }

    @Test
    @DisplayName("shouldHoldGroupNearPlayer: no player near (sentinel window) → never hold")
    void holdGroup_notNear() {
        // sentinel window (MAX,MIN) passed when no player is near
        assertFalse(TrainCarriageAppender.shouldHoldGroupNearPlayer(
            false, 6, 8, Integer.MAX_VALUE, Integer.MIN_VALUE));
        // even if the flag were mistakenly true, an inverted window holds nothing
        assertFalse(TrainCarriageAppender.shouldHoldGroupNearPlayer(
            true, 6, 8, Integer.MAX_VALUE, Integer.MIN_VALUE));
    }

    // preSeedDesiredX is the appender's spawn-placement target: the origin whose seam against the
    // reference measures exactly TARGET_GAP_BLOCKS. Combined with nearest-integer rounding plus the
    // fractional remainder carried through preSeedGapShift, an appended group must land INSIDE the
    // placement tracker's clean dead-band — otherwise it has to run a move-together pass first,
    // which is the opening leg of the collide→move-together→collide cycle that stalled backward
    // generation.

    /** Replays planSpawnPlacement's rounding + remainder split and returns the realised seam gap. */
    private static double realisedGap(double idealX, boolean forward) {
        double desired = TrainCarriageAppender.preSeedDesiredX(idealX, forward);
        long placeX = Math.round(desired);
        double effective = placeX + (desired - placeX);   // origin + preSeedGapShift remainder
        return forward ? (effective - idealX) : (idealX - effective);
    }

    @Test
    @DisplayName("preSeedDesiredX: realised seam is exactly TARGET_GAP_BLOCKS for any fractional idealX")
    void preSeed_landsOnTargetGap() {
        for (double frac = 0.0; frac < 1.0; frac += 0.05) {
            double idealX = 1234.0 + frac;
            assertEquals(TrainCarriageAppender.TARGET_GAP_BLOCKS, realisedGap(idealX, true), 1e-9,
                "forward spawn at idealX=" + idealX);
            assertEquals(TrainCarriageAppender.TARGET_GAP_BLOCKS, realisedGap(-idealX, false), 1e-9,
                "backward spawn at idealX=" + (-idealX));
        }
    }

    @Test
    @DisplayName("preSeedDesiredX: realised seam sits inside the tracker's clean dead-band")
    void preSeed_insideCleanBand() {
        for (double frac = 0.0; frac < 1.0; frac += 0.05) {
            for (boolean forward : new boolean[] {true, false}) {
                double gap = realisedGap(500.0 + frac, forward);
                assertTrue(gap >= TrainCarriageAppender.MIN_GAP_BLOCKS
                        && gap <= TrainCarriageAppender.MAX_GAP_BLOCKS,
                    "gap " + gap + " outside dead-band (forward=" + forward + ", frac=" + frac + ")");
            }
        }
    }

    @Test
    @DisplayName("preSeedDesiredX: backward spawns sit BELOW idealX, forward spawns above")
    void preSeed_direction() {
        assertTrue(TrainCarriageAppender.preSeedDesiredX(100.0, true) > 100.0);
        assertTrue(TrainCarriageAppender.preSeedDesiredX(100.0, false) < 100.0);
    }

    // cullLatchExpired bounds the cull-clear latch. Before the expiry the latch holds its lane shut
    // (that's what stops the cull→clear→spawn→cull ghost cascade); at/after it, one attempt is let
    // through — without which a single ill-timed Sable cull halted backward generation for the whole
    // session and the train simply ended.

    @Test
    @DisplayName("cullLatchExpired: false inside the window, true at and past the boundary")
    void cullLatch_boundary() {
        long stamped = 10_000L;
        long expiry = TrainCarriageAppender.CULL_LATCH_EXPIRY_TICKS;
        assertFalse(TrainCarriageAppender.cullLatchExpired(stamped, stamped));
        assertFalse(TrainCarriageAppender.cullLatchExpired(stamped, stamped + expiry - 1));
        assertTrue(TrainCarriageAppender.cullLatchExpired(stamped, stamped + expiry));
        assertTrue(TrainCarriageAppender.cullLatchExpired(stamped, stamped + expiry * 10));
    }

    @Test
    @DisplayName("cullLatchExpired: the window is long enough to outlast a normal settle")
    void cullLatch_windowOutlastsSettle() {
        assertTrue(TrainCarriageAppender.CULL_LATCH_EXPIRY_TICKS > 200L,
            "expiry must exceed the placement safety valve, or it would race a normal settle");
    }

    // A gap wider than the tracker can nudge closed is not a settling error — it is a group that has
    // fallen out of the train (observed live: physics starved for ~5s during cold worldgen, group
    // ended up 67.5 blocks behind its real neighbour). Nudging at 0.5 per 4 ticks can close ~25
    // blocks in the whole settle budget, so chasing only burns the budget; one corrective step puts
    // the seam straight on target.

    @Test
    @DisplayName("isUnreachableGap: true only past the threshold, never while colliding, never on infinity")
    void unreachableGap_predicate() {
        assertTrue(TrainCarriageAppender.isUnreachableGap(false, 67.5));
        assertTrue(TrainCarriageAppender.isUnreachableGap(false,
            TrainCarriageAppender.LARGE_GAP_REPLACE_BLOCKS + 0.01));
        assertFalse(TrainCarriageAppender.isUnreachableGap(false,
            TrainCarriageAppender.LARGE_GAP_REPLACE_BLOCKS),
            "the threshold itself is still nudgeable");
        assertFalse(TrainCarriageAppender.isUnreachableGap(false, 1.6),
            "the worst legitimate spawn offset must never trigger a re-place");
        assertFalse(TrainCarriageAppender.isUnreachableGap(true, 67.5),
            "an overlap is the pushback branch's job");
        assertFalse(TrainCarriageAppender.isUnreachableGap(false, Double.POSITIVE_INFINITY),
            "no trustworthy neighbour is not a separation");
    }

    @Test
    @DisplayName("placementTrackerReplaceDx: one step lands the seam exactly on target, both directions")
    void replaceDx_landsOnTarget() {
        double target = TrainCarriageAppender.TARGET_GAP_BLOCKS;

        double backward = TrainCarriageAppender.placementTrackerReplaceDx(67.5, true);
        assertTrue(backward > 0, "a backward-spawned group closes toward +X");
        assertEquals(target, 67.5 - backward, 1e-9);

        double forward = TrainCarriageAppender.placementTrackerReplaceDx(67.5, false);
        assertTrue(forward < 0, "a forward-spawned group closes toward -X");
        assertEquals(target, 67.5 + forward, 1e-9);
    }

    @Test
    @DisplayName("the re-place threshold sits above what a nudge could close, and the confirm window is real")
    void replaceThresholdIsSane() {
        assertTrue(TrainCarriageAppender.LARGE_GAP_REPLACE_BLOCKS > 1.6,
            "must clear the worst legitimate spawn offset");
        assertTrue(TrainCarriageAppender.LARGE_GAP_CONFIRM_TICKS >= 2,
            "one frame of stale geometry must never teleport a carriage");
    }

    // ---- catch-up burst ----------------------------------------------------
    //
    // The per-lane placement gate paces spawning at one group per settle
    // window. catchUpBurstGroups is the ONLY thing that lets a lane exceed
    // that, so its boundary is where "steady state unchanged" is enforced.

    @Test
    @DisplayName("catchUpBurstGroups: a covered lane keeps the one-group cadence")
    void burst_coveredLane_noBurst() {
        assertEquals(1, TrainCarriageAppender.catchUpBurstGroups(0, GROUP_SIZE));
        assertEquals(1, TrainCarriageAppender.catchUpBurstGroups(-9, GROUP_SIZE),
            "a lane already past the needed window is not behind");
    }

    @Test
    @DisplayName("catchUpBurstGroups: one group behind is ordinary extension, not a burst")
    void burst_oneGroupBehind_noBurst() {
        assertEquals(1, TrainCarriageAppender.catchUpBurstGroups(1, GROUP_SIZE));
        assertEquals(1, TrainCarriageAppender.catchUpBurstGroups(GROUP_SIZE, GROUP_SIZE),
            "exactly one group short is what every normal spawn starts from");
    }

    @Test
    @DisplayName("catchUpBurstGroups: two groups behind engages the burst")
    void burst_twoGroupsBehind_bursts() {
        assertEquals(TrainCarriageAppender.CATCH_UP_BURST_GROUPS,
            TrainCarriageAppender.catchUpBurstGroups(GROUP_SIZE + 1, GROUP_SIZE),
            "a partial second group still means the lane cannot cover the window this spawn");
        assertEquals(TrainCarriageAppender.CATCH_UP_BURST_GROUPS,
            TrainCarriageAppender.catchUpBurstGroups(2 * GROUP_SIZE, GROUP_SIZE));
    }

    @Test
    @DisplayName("catchUpBurstGroups: far behind is still capped at the burst size")
    void burst_farBehind_capped() {
        assertEquals(TrainCarriageAppender.CATCH_UP_BURST_GROUPS,
            TrainCarriageAppender.catchUpBurstGroups(40 * GROUP_SIZE, GROUP_SIZE),
            "the burst is a rate bump, not an unbounded fill");
    }

    @Test
    @DisplayName("catchUpBurstGroups: groupSize 1 measures the deficit in carriages")
    void burst_groupSizeOne() {
        assertEquals(1, TrainCarriageAppender.catchUpBurstGroups(1, 1));
        assertEquals(TrainCarriageAppender.CATCH_UP_BURST_GROUPS,
            TrainCarriageAppender.catchUpBurstGroups(2, 1));
    }

    @Test
    @DisplayName("catchUpBurstGroups: a non-positive groupSize throws")
    void burst_groupSizeZero_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> TrainCarriageAppender.catchUpBurstGroups(9, 0));
    }

    @Test
    @DisplayName("chainedSpawnDesiredX: one whole stride plus the target seam, both directions")
    void chainedStride_landsOnTargetGap() {
        double target = TrainCarriageAppender.TARGET_GAP_BLOCKS;
        int stride = 31;
        double prevX = 120.25;

        double forward = TrainCarriageAppender.chainedSpawnDesiredX(prevX, stride, true);
        assertEquals(target, forward - (prevX + stride), 1e-9,
            "the forward seam is measured from the previous group's far edge");

        double backward = TrainCarriageAppender.chainedSpawnDesiredX(prevX, stride, false);
        assertEquals(target, (prevX - stride) - backward, 1e-9,
            "the backward seam mirrors it");
    }

    @Test
    @DisplayName("chainedSpawnDesiredX: the seam lands inside the tracker's clean dead-band")
    void chainedStride_startsInBand() {
        double gap = TrainCarriageAppender.chainedSpawnDesiredX(0.0, 31, true) - 31.0;
        assertTrue(gap >= TrainCarriageAppender.MIN_GAP_BLOCKS
                && gap <= TrainCarriageAppender.MAX_GAP_BLOCKS,
            "a burst group must not need a shift pass to settle");
    }

    @Test
    @DisplayName("burstChainIsCommittable: only an unshoved chained placement may be committed")
    void burstChain_onlyCleanPlacementsCommit() {
        assertTrue(TrainCarriageAppender.burstChainIsCommittable(0),
            "a placement that landed exactly where the stride put it is the chain's premise");
        assertFalse(TrainCarriageAppender.burstChainIsCommittable(-116),
            "the 116-block shove observed in play must abandon the burst, not place a hole");
        assertFalse(TrainCarriageAppender.burstChainIsCommittable(-1),
            "even a one-block shove means something already sits where the chain expected free space");
        assertFalse(TrainCarriageAppender.burstChainIsCommittable(1),
            "direction of the shove is irrelevant — any correction voids the chain");
    }

    @Test
    @DisplayName("chainedSpawnDesiredX: chaining twice never accumulates drift")
    void chainedStride_chainsWithoutDrift() {
        int stride = 31;
        double step = stride + TrainCarriageAppender.TARGET_GAP_BLOCKS;
        double first = TrainCarriageAppender.chainedSpawnDesiredX(0.0, stride, true);
        double second = TrainCarriageAppender.chainedSpawnDesiredX(first, stride, true);
        assertEquals(2 * step, second, 1e-9);
    }
}
