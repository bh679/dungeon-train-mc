package games.brennan.dungeontrain.client.snapshot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link SnapshotCooldowns}: the first shot of a tag fires
 * immediately (on its trigger); taking it starts an escalating time + carriage
 * cooldown for the rest. No NeoForge bootstrap — the class takes ticks + progress
 * as parameters.
 */
final class SnapshotCooldownsTest {

    private static final long UNIT = SnapshotCooldowns.ONE_MINUTE_TICKS; // a context-tag cooldown unit (1 min)

    @Test
    @DisplayName("first shot of a tag fires immediately, with no time/carriage required")
    void firstShot_immediate() {
        SnapshotCooldowns cd = new SnapshotCooldowns();
        assertTrue(cd.due(SnapshotTag.COMBAT, 1000, 0, UNIT));
        assertTrue(cd.due(SnapshotTag.SCENIC, 1000, 0, UNIT));
    }

    @Test
    @DisplayName("the first shot starts the cooldown: the next needs 1 unit AND 1 carriage")
    void afterFirst_dualGate() {
        SnapshotCooldowns cd = new SnapshotCooldowns();
        cd.onCommitted(SnapshotTag.COMBAT, 1000, 0); // shot #1 (fired on its trigger)
        assertFalse(cd.due(SnapshotTag.COMBAT, 1000, 0, UNIT), "nothing elapsed");
        assertFalse(cd.due(SnapshotTag.COMBAT, 1000 + UNIT, 0, UNIT), "time met, 0 carriages");
        assertFalse(cd.due(SnapshotTag.COMBAT, 1000, 1, UNIT), "carriage met, no time");
        assertTrue(cd.due(SnapshotTag.COMBAT, 1000 + UNIT, 1, UNIT), "1 unit + 1 carriage");
    }

    @Test
    @DisplayName("time cooldown escalates 1, 2, 3, 4 units after each shot")
    void timeGate_escalates() {
        SnapshotCooldowns cd = new SnapshotCooldowns();
        long t = 1000;
        int p = 0;
        cd.onCommitted(SnapshotTag.SCENIC, t, p); // shot #1 (immediate)
        for (int shot = 2; shot <= 5; shot++) {
            long threshold = (long) (shot - 1) * UNIT; // 1u before #2, 2u before #3, ...
            assertFalse(cd.due(SnapshotTag.SCENIC, t + threshold - 1, p + 1000, UNIT),
                    "shot " + shot + " not due one tick early");
            assertTrue(cd.due(SnapshotTag.SCENIC, t + threshold, p + 1000, UNIT),
                    "shot " + shot + " due at threshold");
            t += threshold;
            p += 1000;
            cd.onCommitted(SnapshotTag.SCENIC, t, p);
        }
    }

    @Test
    @DisplayName("carriage cooldown escalates 1, 4, 8, 13 after each shot")
    void carriageGate_escalates() {
        SnapshotCooldowns cd = new SnapshotCooldowns();
        cd.onCommitted(SnapshotTag.GEAR, 1000, 0); // shot #1 (immediate)
        int[] expected = { 1, 4, 8, 13 };          // carriage thresholds for shots #2..#5
        long far = 1000;
        int p = 0;
        for (int idx = 0; idx < expected.length; idx++) {
            int need = expected[idx];
            far += 10_000_000; // time always satisfied → isolate the carriage gate
            assertFalse(cd.due(SnapshotTag.GEAR, far, p + need - 1, UNIT),
                    "shot " + (idx + 2) + " not due at " + (need - 1) + " carriages");
            assertTrue(cd.due(SnapshotTag.GEAR, far, p + need, UNIT),
                    "shot " + (idx + 2) + " due at " + need + " carriages");
            cd.onCommitted(SnapshotTag.GEAR, far, p + need);
            p += need;
        }
    }

    @Test
    @DisplayName("categories are independent (committing COMBAT doesn't gate SCENIC's first shot)")
    void perTag_independent() {
        SnapshotCooldowns cd = new SnapshotCooldowns();
        cd.onCommitted(SnapshotTag.COMBAT, 1000, 0);
        cd.onCommitted(SnapshotTag.COMBAT, 1000 + UNIT, 1);
        assertEquals(2, cd.count(SnapshotTag.COMBAT));
        assertTrue(cd.due(SnapshotTag.SCENIC, 1000, 0, UNIT), "SCENIC's first shot is still immediate");
        assertEquals(0, cd.count(SnapshotTag.SCENIC));
    }

    @Test
    @DisplayName("a regressing progress counter clamps to 0 (waits, never fires early)")
    void progressDelta_clamped() {
        SnapshotCooldowns cd = new SnapshotCooldowns();
        cd.onCommitted(SnapshotTag.GEAR, 1000, 10); // shot #1; next needs 1 unit + 1 carriage
        assertFalse(cd.due(SnapshotTag.GEAR, 1000 + 10_000_000, 5, UNIT), "progress regressed → clamp to 0");
        assertTrue(cd.due(SnapshotTag.GEAR, 1000 + 10_000_000, 11, UNIT), "1 carriage past the last shot");
    }

    @Test
    @DisplayName("reset clears state; the first shot is immediate again")
    void reset_clears() {
        SnapshotCooldowns cd = new SnapshotCooldowns();
        cd.onCommitted(SnapshotTag.SCENIC, 5000, 3);
        cd.reset();
        assertEquals(0, cd.count(SnapshotTag.SCENIC));
        assertTrue(cd.due(SnapshotTag.SCENIC, 9000, 3, UNIT));
    }

    @Test
    @DisplayName("a perf-skip holds the tag off for SKIP_RETRY_TICKS, then it is due again")
    void skip_backsOffFixedWindow() {
        SnapshotCooldowns cd = new SnapshotCooldowns();
        cd.onCommitted(SnapshotTag.SCENIC, 1000, 0); // shot #1
        long due = 1000 + UNIT;                       // 2nd shot would normally be due here (1 unit + 1 carriage)
        assertTrue(cd.due(SnapshotTag.SCENIC, due, 1, UNIT), "2nd shot due at the normal gate");
        cd.onSkipped(SnapshotTag.SCENIC, due);        // ...but the game was lagging, so it was skipped
        assertFalse(cd.due(SnapshotTag.SCENIC, due, 1, UNIT), "skipped → held off");
        assertFalse(cd.due(SnapshotTag.SCENIC, due + SnapshotCooldowns.SKIP_RETRY_TICKS - 1, 1, UNIT),
                "still held one tick early");
        assertTrue(cd.due(SnapshotTag.SCENIC, due + SnapshotCooldowns.SKIP_RETRY_TICKS, 1, UNIT),
                "due again after the 20s back-off");
    }

    @Test
    @DisplayName("a skip is not a shot: it never advances the escalating cooldown; a real shot clears it")
    void skip_doesNotAdvanceCount_andCommitClears() {
        SnapshotCooldowns cd = new SnapshotCooldowns();
        cd.onCommitted(SnapshotTag.GEAR, 1000, 0);    // shot #1, count=1
        long due = 1000 + UNIT;
        cd.onSkipped(SnapshotTag.GEAR, due);
        assertEquals(1, cd.count(SnapshotTag.GEAR), "a skip is not a shot");
        cd.onCommitted(SnapshotTag.GEAR, due + 10, 1); // a real 2nd shot during the back-off window clears it
        assertEquals(2, cd.count(SnapshotTag.GEAR));
        assertFalse(cd.due(SnapshotTag.GEAR, due + 10 + UNIT, 5, UNIT), "3rd shot needs 2 units (escalated normally)");
        assertTrue(cd.due(SnapshotTag.GEAR, due + 10 + 2 * UNIT, 5, UNIT), "due at 2 units + carriages");
    }

    @Test
    @DisplayName("a skip can hold off even the first (uncommitted) shot")
    void skip_firstShot() {
        SnapshotCooldowns cd = new SnapshotCooldowns();
        assertTrue(cd.due(SnapshotTag.COMBAT, 1000, 0, UNIT), "first shot is immediate");
        cd.onSkipped(SnapshotTag.COMBAT, 1000);
        assertFalse(cd.due(SnapshotTag.COMBAT, 1000, 0, UNIT), "skipped first shot held off");
        assertTrue(cd.due(SnapshotTag.COMBAT, 1000 + SnapshotCooldowns.SKIP_RETRY_TICKS, 0, UNIT),
                "due after the 20s back-off");
    }
}
