package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.train.TransitionFlatbedPlan.Lead;
import games.brennan.dungeontrain.worldgen.TransitionLandmarks.Landmark;
import games.brennan.dungeontrain.worldgen.TransitionLandmarks.Timing;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure prediction / claim maths behind transition flatbeds. */
final class TransitionFlatbedPlanTest {

    /** Default train: 9-long carriages, groups of 3 with 5-block pads, 0.4 seam → stride 37.4, pitch ~12.47. */
    static final int LEN = 9;
    static final int G = 3;
    static final double STRIDE = 3 * 9 + 2 * 5 + 0.4;
    static final double PITCH = STRIDE / G;

    static Landmark timed(long x, int count) {
        return new Landmark("t@r0", "t", x, Timing.TIMED, count);
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
        assertNull(TransitionFlatbedPlan.claimFor(lm, lead, 2.0, groupAnchor - G, G, 0, LEN, STRIDE));
        assertArrayEquals(new int[]{target}, TransitionFlatbedPlan.claimFor(lm, lead, 2.0, groupAnchor, G, 0, LEN, STRIDE));
    }

    @Test
    @DisplayName("three in a row end at the target; a run reaching behind the group starts at its anchor")
    void threeInARow() {
        Lead lead = new Lead(0, 0.0, 0.25);
        Landmark lm = timed(1000L, 3);
        int target = TransitionFlatbedPlan.predictTarget(0, 0.0, 1000L, 2.0, 0.25, PITCH);
        int anchor = Math.floorDiv(target, G) * G;
        int[] claim = TransitionFlatbedPlan.claimFor(lm, lead, 2.0, anchor, G, 0, LEN, STRIDE);
        int first = Math.max(anchor, target - 2);
        assertArrayEquals(new int[]{first, first + 1, first + 2}, claim);
    }

    @Test
    @DisplayName("late predictions land on the first unbuilt carriage, unless too late")
    void late() {
        Lead lead = new Lead(0, 990.0, 0.25);     // 10 blocks out: target ≈ pIdx 0
        Landmark lm = timed(1000L, 1);
        assertArrayEquals(new int[]{6}, TransitionFlatbedPlan.claimFor(lm, lead, 2.0, 6, G, 0, LEN, STRIDE));
        assertNull(TransitionFlatbedPlan.claimFor(lm, lead, 2.0, 12, G, 0, LEN, STRIDE));
        // Already past the landmark: nothing.
        assertNull(TransitionFlatbedPlan.claimFor(lm, new Lead(0, 1001.0, 0.25), 2.0, 3, G, 0, LEN, STRIDE));
        assertNull(TransitionFlatbedPlan.claimFor(lm, null, 2.0, 3, G, 0, LEN, STRIDE));
    }

    @Test
    @DisplayName("first-carriage landmarks take the first slot at or past the line, if spawned near it")
    void firstCarriage() {
        Landmark lm = new Landmark("f@r0", "f", 1000L, Timing.FIRST_CARRIAGE, 1);
        // Group's first enclosed carriage at X 985: slots at 985, 994, 1003 → the third.
        assertArrayEquals(new int[]{32}, TransitionFlatbedPlan.claimFor(lm, null, 2.0, 30, G, 985, LEN, STRIDE));
        // Whole group before the line: not yet.
        assertNull(TransitionFlatbedPlan.claimFor(lm, null, 2.0, 27, G, 940, LEN, STRIDE));
        // Spawned long after the line went by: nothing.
        assertNull(TransitionFlatbedPlan.claimFor(lm, null, 2.0, 300, G, 5000, LEN, STRIDE));
    }

    @Test
    @DisplayName("pace: an EMA of forward carriages per second, backward steps count as still")
    void pace() {
        PlayerCarriagePace p = new PlayerCarriagePace(0.0);
        // one carriage every 2 s (40 ticks) for ten minutes
        int pIdx = 0;
        for (long tick = 0; tick <= 12_000; tick++) {
            if (tick > 0 && tick % 40 == 0) pIdx++;
            p.observe(pIdx, tick);
        }
        assertEquals(0.5, p.pace(), 0.03);
        // walking backward reads as standing: the average decays, never goes negative
        for (long tick = 12_001; tick <= 24_000; tick++) {
            if (tick % 40 == 0) pIdx--;
            p.observe(pIdx, tick);
        }
        assertTrue(p.pace() >= 0.0 && p.pace() < 0.01);
    }
}
