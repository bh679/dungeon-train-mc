package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.train.TrainCarriageAppender.TrailingId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    // ---- Approach 2: stuck-ghost classification ----
    //
    // classifyAnchor decides what a registry anchor is so the frontier logic can
    // compute the spawnable extent (VISIBLE ∪ RELOADABLE) and drop unrecoverable
    // STUCK ghosts. The precedence (visible → reloadable → held → residentAabb)
    // mirrors decideEdgeAction: held is checked before a (possibly stale) resident
    // AABB so a held-but-not-reloadable null-pointer ghost is correctly STUCK.

    @Test
    @DisplayName("classifyAnchor: visible → VISIBLE (wins over every other flag)")
    void classify_visible_wins() {
        assertEquals(TrainCarriageAppender.AnchorClass.VISIBLE,
            TrainCarriageAppender.classifyAnchor(true, false, false, false));
        assertEquals(TrainCarriageAppender.AnchorClass.VISIBLE,
            TrainCarriageAppender.classifyAnchor(true, true, true, true));
    }

    @Test
    @DisplayName("classifyAnchor: held WITH pointer (reloadable) → RELOADABLE (in the extent)")
    void classify_reloadable() {
        assertEquals(TrainCarriageAppender.AnchorClass.RELOADABLE,
            TrainCarriageAppender.classifyAnchor(false, true, false, false));
        // reloadable wins over held (a reloadable sub-level is also held).
        assertEquals(TrainCarriageAppender.AnchorClass.RELOADABLE,
            TrainCarriageAppender.classifyAnchor(false, true, true, false));
    }

    @Test
    @DisplayName("classifyAnchor: held but NOT reloadable (null-pointer ghost) → STUCK")
    void classify_heldNullPointer_stuck() {
        assertEquals(TrainCarriageAppender.AnchorClass.STUCK,
            TrainCarriageAppender.classifyAnchor(false, false, true, false));
    }

    @Test
    @DisplayName("classifyAnchor: held beats a stale resident AABB → STUCK (no respawn-against-stale-pose)")
    void classify_heldBeatsStaleResidentAabb() {
        // A held ghost's registry wrapper can still report a non-zero AABB. held
        // MUST win so it's dropped, not treated as a live resident dropout.
        assertEquals(TrainCarriageAppender.AnchorClass.STUCK,
            TrainCarriageAppender.classifyAnchor(false, false, true, true));
    }

    @Test
    @DisplayName("classifyAnchor: not held, resident with a live AABB → RESIDENT_LIVE (transient dropout)")
    void classify_residentLive() {
        assertEquals(TrainCarriageAppender.AnchorClass.RESIDENT_LIVE,
            TrainCarriageAppender.classifyAnchor(false, false, false, true));
    }

    @Test
    @DisplayName("classifyAnchor: not visible, not reloadable, not held, no live AABB → STUCK (gone)")
    void classify_gone_stuck() {
        assertEquals(TrainCarriageAppender.AnchorClass.STUCK,
            TrainCarriageAppender.classifyAnchor(false, false, false, false));
    }
}
