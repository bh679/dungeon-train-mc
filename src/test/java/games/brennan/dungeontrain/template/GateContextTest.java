package games.brennan.dungeontrain.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-math tests for {@link GateContext#groupAnchorPIdx} — the sub-level group anchor that makes
 * every car in a Sable sub-level group resolve its spawn-gate dimension from one shared overworld
 * position (so a group straddling a band edge themes uniformly). The {@code ServerLevel}-bound
 * {@code forCarriage} resolver is exercised in-game; this locks the tiling formula.
 */
final class GateContextTest {

    @Test
    @DisplayName("groups of 3 share one anchor; the anchor is the group's lowest pIdx")
    void groupsOfThree() {
        // Forward groups: [0,1,2]→0, [3,4,5]→3, [6,..]→6
        assertEquals(0, GateContext.groupAnchorPIdx(0, 3));
        assertEquals(0, GateContext.groupAnchorPIdx(1, 3));
        assertEquals(0, GateContext.groupAnchorPIdx(2, 3));
        assertEquals(3, GateContext.groupAnchorPIdx(3, 3));
        assertEquals(3, GateContext.groupAnchorPIdx(4, 3));
        assertEquals(3, GateContext.groupAnchorPIdx(5, 3));
        assertEquals(6, GateContext.groupAnchorPIdx(6, 3));
    }

    @Test
    @DisplayName("backward (negative) carriages tile the same way via floorDiv")
    void backwardGroups() {
        // [-3,-2,-1]→-3, [-6,-5,-4]→-6
        assertEquals(-3, GateContext.groupAnchorPIdx(-1, 3));
        assertEquals(-3, GateContext.groupAnchorPIdx(-2, 3));
        assertEquals(-3, GateContext.groupAnchorPIdx(-3, 3));
        assertEquals(-6, GateContext.groupAnchorPIdx(-4, 3));
    }

    @Test
    @DisplayName("groupSize 1 degenerates to per-carriage; non-positive sizes clamp to 1")
    void degenerateSizes() {
        assertEquals(7, GateContext.groupAnchorPIdx(7, 1));
        assertEquals(-7, GateContext.groupAnchorPIdx(-7, 1));
        assertEquals(5, GateContext.groupAnchorPIdx(5, 0));
        assertEquals(5, GateContext.groupAnchorPIdx(5, -2));
    }

    @Test
    @DisplayName("every member of a group maps to the identical anchor")
    void groupMembersAgree() {
        for (int groupSize : new int[] {2, 3, 4, 5}) {
            for (int anchor = -2 * groupSize; anchor <= 2 * groupSize; anchor += groupSize) {
                for (int slot = 0; slot < groupSize; slot++) {
                    assertEquals(anchor, GateContext.groupAnchorPIdx(anchor + slot, groupSize),
                        "pIdx " + (anchor + slot) + " (groupSize " + groupSize + ") should anchor to " + anchor);
                }
            }
        }
    }
}
