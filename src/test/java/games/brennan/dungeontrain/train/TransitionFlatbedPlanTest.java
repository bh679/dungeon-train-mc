package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.train.TransitionFlatbedPlan.Lead;
import games.brennan.dungeontrain.worldgen.TransitionLandmarks.Landmark;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure prediction / claim maths behind transition flatbeds. */
final class TransitionFlatbedPlanTest {

    /** Default train: 9-long carriages, groups of 3 with 5-block pads, 0.4 seam → stride 37.4, pitch ~12.47. */
    static final int G = 3;
    static final double STRIDE = 3 * 9 + 2 * 5 + 0.4;
    static final double PITCH = STRIDE / G;

    static Landmark timed(long x, int count) {
        return new Landmark("t@r0", "t", x, count);
    }

    @Test
    @DisplayName("prediction: walked carriages = pace × time to close the distance")
    void predict() {
        // 1000 blocks at 2 b/s + 0.25 c/s × 12.47 = 5.117 b/s → 195.4 s → 48.9 carriages
        int t = TransitionFlatbedPlan.predictTarget(100, 0.0, 1000L, 2.0, 0.25, PITCH);
        double expected = 0.25 * 1000.0 / (2.0 + 0.25 * PITCH);
        assertEquals(100 + (int) Math.round(expected), t);
        // Faster walker: more carriages before the line, but the line arrives sooner too.
        assertTrue(TransitionFlatbedPlan.predictTarget(100, 0.0, 1000L, 2.0, 1.0, PITCH) > t);
    }

    @Test
    @DisplayName("an idle player still gets a target ahead (pace floor); a still train and player never arrive")
    void idleAndStill() {
        int idle = TransitionFlatbedPlan.predictTarget(10, 0.0, 400L, 2.0, 0.0, PITCH);
        assertEquals(10 + (int) Math.round(TransitionFlatbedPlan.MIN_PACE * 400.0 / (2.0 + TransitionFlatbedPlan.MIN_PACE * PITCH)), idle);
        assertEquals(TransitionFlatbedPlan.UNREACHABLE, TransitionFlatbedPlan.predictTarget(10, 0.0, 400L, 0.0, 0.0, 0.0));
        // Train stopped, player walking: the walking alone gets them there.
        int walk = TransitionFlatbedPlan.predictTarget(10, 0.0, 400L, 0.0, 0.5, PITCH);
        assertEquals(10 + (int) Math.round(400.0 / PITCH), walk);
    }

    @Test
    @DisplayName("a timed claim waits until the group holding the target spawns")
    void timedWaitsForItsGroup() {
        Lead lead = new Lead(0, 0.0, 0.25);
        Landmark lm = timed(1000L, 1);
        int target = TransitionFlatbedPlan.predictTarget(0, 0.0, 1000L, 2.0, 0.25, PITCH);
        int groupAnchor = Math.floorDiv(target, G) * G;
        assertNull(TransitionFlatbedPlan.claimFor(lm, lead, 2.0, groupAnchor - G, G, STRIDE));
        assertArrayEquals(new int[]{target}, TransitionFlatbedPlan.claimFor(lm, lead, 2.0, groupAnchor, G, STRIDE));
    }

    @Test
    @DisplayName("three in a row end at the target; a run reaching behind the group starts at its anchor")
    void threeInARow() {
        Lead lead = new Lead(0, 0.0, 0.25);
        Landmark lm = timed(1000L, 3);
        int target = TransitionFlatbedPlan.predictTarget(0, 0.0, 1000L, 2.0, 0.25, PITCH);
        int anchor = Math.floorDiv(target, G) * G;
        int[] claim = TransitionFlatbedPlan.claimFor(lm, lead, 2.0, anchor, G, STRIDE);
        int first = Math.max(anchor, target - 2);
        assertArrayEquals(new int[]{first, first + 1, first + 2}, claim);
    }

    @Test
    @DisplayName("late predictions land on the first unbuilt carriage, unless too late")
    void late() {
        Lead lead = new Lead(0, 990.0, 0.25);     // 10 blocks out: target ≈ pIdx 0
        Landmark lm = timed(1000L, 1);
        assertArrayEquals(new int[]{6}, TransitionFlatbedPlan.claimFor(lm, lead, 2.0, 6, G, STRIDE));
        assertNull(TransitionFlatbedPlan.claimFor(lm, lead, 2.0, 12, G, STRIDE));
        // Already past the landmark: nothing.
        assertNull(TransitionFlatbedPlan.claimFor(lm, new Lead(0, 1001.0, 0.25), 2.0, 3, G, STRIDE));
        assertNull(TransitionFlatbedPlan.claimFor(lm, null, 2.0, 3, G, STRIDE));
    }

    @Test
    @DisplayName("a target inside a portal group (no claim there) slides on to the next group")
    void timedTargetInPortalGroupSlidesToNextGroup() {
        Lead lead = new Lead(0, 0.0, 0.25);
        Landmark lm = timed(1000L, 1);
        int target = TransitionFlatbedPlan.predictTarget(0, 0.0, 1000L, 2.0, 0.25, PITCH);
        int portalAnchor = Math.floorDiv(target, G) * G;
        // forcedSlots never asks the portal group; the next group is the first to decide, and takes its anchor.
        assertArrayEquals(new int[]{portalAnchor + G},
                TransitionFlatbedPlan.claimFor(lm, lead, 2.0, portalAnchor + G, G, STRIDE));
    }

    static final int WINDOW = 80;

    /** Feed one carriage every {@code ticksPer} ticks for {@code carriages} carriages; returns the next tick. */
    static long walk(PlayerCarriagePace p, int[] pIdx, long tick, int carriages, int ticksPer) {
        for (int c = 0; c < carriages; c++) {
            for (int t = 0; t < ticksPer; t++) p.observe(pIdx[0], tick++);
            pIdx[0]++;
        }
        p.observe(pIdx[0], tick);
        return tick;
    }

    @Test
    @DisplayName("pace: starts at the seed and tracks forward carriages per second over the window")
    void pace() {
        PlayerCarriagePace p = new PlayerCarriagePace(0.25, WINDOW);
        assertEquals(0.25, p.pace(), 1e-9);
        int[] pIdx = {0};
        // one carriage every 2 s for 200 carriages: well past the window, the prior is a small share
        walk(p, pIdx, 0, 200, 40);
        double expected = (WINDOW + 0.25 * PlayerCarriagePace.PRIOR_SECONDS) / (WINDOW * 2.0 + PlayerCarriagePace.PRIOR_SECONDS);
        assertEquals(expected, p.pace(), 0.01);
    }

    @Test
    @DisplayName("pace: pauses slow it, backward steps count as still, long gaps are not riding time")
    void pacePausesAndGaps() {
        PlayerCarriagePace p = new PlayerCarriagePace(0.25, WINDOW);
        int[] pIdx = {0};
        long tick = walk(p, pIdx, 0, 40, 20);   // 1 c/s
        double walking = p.pace();
        // stand still a minute: the average falls
        for (int t = 0; t < 1200; t++) p.observe(pIdx[0], tick++);
        double paused = p.pace();
        assertTrue(paused < walking * 0.6, paused + " vs " + walking);
        // walk backward a while: still never negative, and no faster
        for (int t = 0; t < 400; t++) { if (t % 20 == 0) pIdx[0]--; p.observe(pIdx[0], tick++); }
        assertTrue(p.pace() >= 0.0 && p.pace() <= paused);
        // ten minutes off the train (one observation gap) is not counted
        double before = p.pace();
        tick += 12_000;
        p.observe(pIdx[0], tick);
        assertEquals(before, p.pace(), 1e-9);
    }

    @Test
    @DisplayName("pace: a fast burst older than the window no longer counts; reset returns to the seed")
    void paceWindowAndReset() {
        PlayerCarriagePace p = new PlayerCarriagePace(0.25, WINDOW);
        int[] pIdx = {0};
        long tick = walk(p, pIdx, 0, 100, 2);      // 10 c/s burst (flying)
        assertTrue(p.pace() > 2.0, "burst " + p.pace());
        walk(p, pIdx, tick, WINDOW + 5, 40);       // then a full window at 0.5 c/s
        assertEquals(0.5, p.pace(), 0.05);
        p.reset();
        assertEquals(0.25, p.pace(), 1e-9);
    }
}
