package games.brennan.dungeontrain.client.snapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link SnapshotCooldowns}: the per-tag dual gate (escalating
 * time AND escalating carriage progress — both required), per-tag independence, and
 * the progress-delta clamp. No NeoForge bootstrap — the class takes ticks + progress
 * as parameters.
 */
final class SnapshotCooldownsTest {

    private static final long MIN = SnapshotCooldowns.ONE_MINUTE_TICKS; // 1200 ticks
    private static final long SEED = MIN;                               // a context-tag seed (1 min)

    @Test
    @DisplayName("first shot needs BOTH the time seed and 1 carriage")
    void firstShot_bothGates() {
        SnapshotCooldowns cd = new SnapshotCooldowns();
        // The first due() call anchors the run baseline at (tick=1000, progress=0).
        assertFalse(cd.due(SnapshotTag.COMBAT, 1000, 0, SEED), "nothing elapsed");
        assertFalse(cd.due(SnapshotTag.COMBAT, 1000 + SEED, 0, SEED), "time met, 0 carriages");
        assertFalse(cd.due(SnapshotTag.COMBAT, 1000, 5, SEED), "carriages met, no time");
        assertTrue(cd.due(SnapshotTag.COMBAT, 1000 + SEED, 1, SEED), "both gates met");
    }

    @Test
    @DisplayName("time gate escalates +1 min per shot (carriage gate held satisfied)")
    void timeGate_escalates() {
        SnapshotCooldowns cd = new SnapshotCooldowns();
        long base = 1000;
        cd.baseline(base, 0);
        long t = base;
        int p = 0;
        for (int shot = 1; shot <= 4; shot++) {
            long threshold = SEED + (long) (shot - 1) * MIN; // seed, +1, +2, +3 min
            assertFalse(cd.due(SnapshotTag.SCENIC, t + threshold - 1, p + 1000, SEED),
                    "shot " + shot + " not due one tick early");
            assertTrue(cd.due(SnapshotTag.SCENIC, t + threshold, p + 1000, SEED),
                    "shot " + shot + " due at threshold");
            t += threshold;
            p += 1000;
            cd.onCommitted(SnapshotTag.SCENIC, t, p);
        }
    }

    @Test
    @DisplayName("carriage gate escalates 1, 4, 8, 13, 22 (ceil of X -> X*1.5+2)")
    void carriageGate_escalates() {
        SnapshotCooldowns cd = new SnapshotCooldowns();
        long base = 1000;
        cd.baseline(base, 0);
        int[] expected = { 1, 4, 8, 13, 22 };
        long t = base;
        int p = 0;
        for (int shot = 0; shot < expected.length; shot++) {
            int need = expected[shot];
            long far = t + 1_000_000; // time gate always satisfied → isolate the carriage gate
            assertFalse(cd.due(SnapshotTag.GEAR, far, p + need - 1, SEED),
                    "shot " + (shot + 1) + " not due at " + (need - 1) + " carriages");
            assertTrue(cd.due(SnapshotTag.GEAR, far, p + need, SEED),
                    "shot " + (shot + 1) + " due at " + need + " carriages");
            cd.onCommitted(SnapshotTag.GEAR, far, p + need);
            t = far;
            p += need;
        }
    }

    @Test
    @DisplayName("categories are independent (committing COMBAT doesn't gate SCENIC)")
    void perTag_independent() {
        SnapshotCooldowns cd = new SnapshotCooldowns();
        cd.baseline(1000, 0);
        cd.onCommitted(SnapshotTag.COMBAT, 1000, 0);
        cd.onCommitted(SnapshotTag.COMBAT, 1000, 0);
        // SCENIC is still at its first-shot thresholds.
        assertFalse(cd.due(SnapshotTag.SCENIC, 1000, 0, SEED));
        assertTrue(cd.due(SnapshotTag.SCENIC, 1000 + SEED, 1, SEED));
        assertEquals(2, cd.count(SnapshotTag.COMBAT));
        assertEquals(0, cd.count(SnapshotTag.SCENIC));
    }

    @Test
    @DisplayName("a regressing progress counter clamps to 0 (waits, never fires early)")
    void progressDelta_clamped() {
        SnapshotCooldowns cd = new SnapshotCooldowns();
        cd.baseline(1000, 10);
        cd.onCommitted(SnapshotTag.GEAR, 1000, 10); // x -> 3.5, group threshold ceil = 4
        // Time hugely satisfied, but progress regressed below the last shot → clamp to 0.
        assertFalse(cd.due(SnapshotTag.GEAR, 1000 + 10_000_000, 5, SEED));
        // Recovered + 4 carriages past the last shot → due.
        assertTrue(cd.due(SnapshotTag.GEAR, 1000 + 10_000_000, 10 + 4, SEED));
    }

    @Test
    @DisplayName("reset clears counts and re-anchors the baseline")
    void reset_reanchors() {
        SnapshotCooldowns cd = new SnapshotCooldowns();
        cd.baseline(1000, 0);
        cd.onCommitted(SnapshotTag.SCENIC, 5000, 3);
        cd.reset();
        assertEquals(0, cd.count(SnapshotTag.SCENIC));
        // Next due() re-anchors the baseline at (9000, 0).
        assertFalse(cd.due(SnapshotTag.SCENIC, 9000, 0, SEED));
        assertTrue(cd.due(SnapshotTag.SCENIC, 9000 + SEED, 1, SEED));
    }
}
