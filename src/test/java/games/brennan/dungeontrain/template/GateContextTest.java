package games.brennan.dungeontrain.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-math tests for the two carriage-group frames {@link GateContext#forCarriage} resolves a gate
 * from. {@link GateContext#groupAnchorPIdx} is the carriage-INDEX anchor (Diff-Level frame, matches
 * the HUD/mobs); {@link GateContext#groupRealStartX} is the REAL overworld X of the group (dimension
 * frame, matches the tracks/band — counts the inter-group half-flatbed pads). The
 * {@code ServerLevel}-bound resolvers are exercised in-game; this locks the tiling + pad math.
 *
 * <p>{@code groupRealStartX} is the static estimate of a group's world-X. In real generation the
 * appender places appended groups relative to the previous group's live position plus a per-group
 * gap, so the actual placement drifts ahead of this estimate (see {@link #realStartAheadOfPadFree});
 * {@link GateContext#forCarriageAtWorldX} closes that drift by gating on the carriage's ACTUAL placed
 * world-X instead of this formula. That resolver needs a {@code ServerLevel}, so it is verified
 * in-game, not here.</p>
 */
final class GateContextTest {

    // ---- groupAnchorPIdx: carriage-index frame (Diff-Level) ----

    @Test
    @DisplayName("anchor pIdx: groups of 3 share one anchor (the group's lowest pIdx), forward + backward")
    void anchorTiling() {
        assertEquals(0, GateContext.groupAnchorPIdx(0, 3));
        assertEquals(0, GateContext.groupAnchorPIdx(2, 3));
        assertEquals(3, GateContext.groupAnchorPIdx(3, 3));
        assertEquals(3, GateContext.groupAnchorPIdx(5, 3));
        assertEquals(6, GateContext.groupAnchorPIdx(6, 3));
        // backward (negative) tiles the same way via floorDiv
        assertEquals(-3, GateContext.groupAnchorPIdx(-1, 3));
        assertEquals(-3, GateContext.groupAnchorPIdx(-3, 3));
        assertEquals(-6, GateContext.groupAnchorPIdx(-4, 3));
    }

    @Test
    @DisplayName("anchor pIdx: groupSize 1 is per-carriage; non-positive sizes clamp to 1")
    void anchorDegenerate() {
        assertEquals(7, GateContext.groupAnchorPIdx(7, 1));
        assertEquals(5, GateContext.groupAnchorPIdx(5, 0));
        assertEquals(5, GateContext.groupAnchorPIdx(5, -2));
    }

    // ---- groupRealStartX: real overworld frame (dimension) ----

    @Test
    @DisplayName("real-X: each group of 3 (length 9) starts every subLevelStride = 37 real blocks")
    void realStartTiling() {
        // length 9 → halfPadLen 5 → subLevelStride = 3*9 + 2*5 = 37
        assertEquals(0, GateContext.groupRealStartX(0, 3, 9));
        assertEquals(0, GateContext.groupRealStartX(2, 3, 9));
        assertEquals(37, GateContext.groupRealStartX(3, 3, 9));
        assertEquals(37, GateContext.groupRealStartX(5, 3, 9));
        assertEquals(74, GateContext.groupRealStartX(6, 3, 9));
        // backward
        assertEquals(-37, GateContext.groupRealStartX(-1, 3, 9));
        assertEquals(-74, GateContext.groupRealStartX(-4, 3, 9));
    }

    @Test
    @DisplayName("real-X: groupSize 1 has no pads (stride == length); non-positive clamps to 1")
    void realStartNoPads() {
        assertEquals(63, GateContext.groupRealStartX(7, 1, 9)); // 7 * 9
        assertEquals(45, GateContext.groupRealStartX(5, 1, 9)); // 5 * 9, no pad
        assertEquals(45, GateContext.groupRealStartX(5, 0, 9)); // groupSize 0 → clamp to 1 → 5 * 9
    }

    @Test
    @DisplayName("real-X runs AHEAD of the pad-free pIdx×length frame by the accumulated inter-group pads (the fix)")
    void realStartAheadOfPadFree() {
        int groupSize = 3, length = 9;
        int padPerGroup = 2 * ((length + 1) / 2); // 10
        for (int groupIdx = 1; groupIdx <= 50; groupIdx++) {
            int pIdx = groupIdx * groupSize;
            int realX = GateContext.groupRealStartX(pIdx, groupSize, length);
            int padFreeX = GateContext.groupAnchorPIdx(pIdx, groupSize) * length; // the old (buggy) frame
            assertEquals(padFreeX + groupIdx * padPerGroup, realX,
                "real-X must lead the pad-free frame by groupIdx × padPerGroup at group " + groupIdx);
            assertTrue(realX > padFreeX, "real-X is strictly ahead once past group 0");
        }
        // By ~group 270 (≈ Nether at default owHold) the lead is ≈ 2700 - but even at group 100 it is sizable:
        assertEquals(100 * 10, GateContext.groupRealStartX(100 * 3, 3, 9)
            - GateContext.groupAnchorPIdx(100 * 3, 3) * 9, "≈1000-block class drift by deep groups");
    }

    @Test
    @DisplayName("both frames: every member of a group maps to the identical anchor + real-X")
    void groupMembersAgree() {
        for (int groupSize : new int[] {2, 3, 4, 5}) {
            for (int g = -3; g <= 3; g++) {
                int anchor = g * groupSize;
                int expectedReal = GateContext.groupRealStartX(anchor, groupSize, 9);
                for (int slot = 0; slot < groupSize; slot++) {
                    assertEquals(anchor, GateContext.groupAnchorPIdx(anchor + slot, groupSize));
                    assertEquals(expectedReal, GateContext.groupRealStartX(anchor + slot, groupSize, 9));
                }
            }
        }
    }
}
