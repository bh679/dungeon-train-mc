package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.track.TrackGeometry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for the two pure decisions behind {@code TrainTickEvents.sweepFootprint}'s block-breaking
 * pass: {@link TrainTickEvents#breakFloorY} (the rail-protection floor) and
 * {@link TrainTickEvents#shouldBreak} (enabled + collides + above-floor + within-budget).
 *
 * <p>The rail-floor case is the load-bearing one. Carriage voxels occupy {@code trainY ..
 * trainY+height-1} while {@link TrackGeometry} puts the rails at {@code trainY-1} and the bed at
 * {@code trainY-2}, so the footprint's own AABB already excludes them — but a train that destroys
 * the track it is riding on is an unrecoverable, save-corrupting failure, so the invariant is pinned
 * here rather than left to geometry that a future Y-offset or bounding-box pad could shift.</p>
 */
class TrainBlockBreakGateTest {

    /** Carriage floor Y used throughout; TrackGeometry derives bedY = 98, railY = 99. */
    private static final int TRAIN_Y = 100;

    private static TrackGeometry geometry() {
        return new TrackGeometry(TRAIN_Y - 2, TRAIN_Y - 1, 0, 4);
    }

    @Test
    @DisplayName("break floor sits one above the rail row, so rails and bed are never breakable")
    void breakFloorIsAboveRails() {
        TrackGeometry g = geometry();
        int floor = TrainTickEvents.breakFloorY(g);

        assertEquals(TRAIN_Y, floor, "floor should be the carriage floor Y itself");
        assertTrue(floor > g.railY(), "rail row must be below the floor");
        assertTrue(floor > g.bedY(), "bed row must be below the floor");
    }

    @Test
    @DisplayName("null geometry fails closed — breaking is disabled, never floored at 0")
    void nullGeometryDisablesBreaking() {
        assertEquals(Integer.MAX_VALUE, TrainTickEvents.breakFloorY(null));
        // No reachable Y can clear a MAX_VALUE floor, so the gate is shut for such a carriage.
        assertFalse(TrainTickEvents.shouldBreak(true, true, 320, TrainTickEvents.breakFloorY(null), 0, 256));
    }

    @Test
    @DisplayName("the rail and bed rows are refused while the carriage body rows are allowed")
    void railAndBedRowsAreRefused() {
        int floor = TrainTickEvents.breakFloorY(geometry());

        assertFalse(TrainTickEvents.shouldBreak(true, true, TRAIN_Y - 2, floor, 0, 256), "bed row");
        assertFalse(TrainTickEvents.shouldBreak(true, true, TRAIN_Y - 1, floor, 0, 256), "rail row");
        assertTrue(TrainTickEvents.shouldBreak(true, true, TRAIN_Y, floor, 0, 256), "carriage floor row");
        assertTrue(TrainTickEvents.shouldBreak(true, true, TRAIN_Y + 3, floor, 0, 256), "carriage interior");
    }

    @Test
    @DisplayName("disabled config refuses every cell regardless of height or budget")
    void disabledRefusesEverything() {
        int floor = TrainTickEvents.breakFloorY(geometry());

        assertFalse(TrainTickEvents.shouldBreak(false, true, TRAIN_Y, floor, 0, 256));
        assertFalse(TrainTickEvents.shouldBreak(false, true, TRAIN_Y + 3, floor, 0, 256));
    }

    @Test
    @DisplayName("budget admits cells up to the cap and refuses at and beyond it")
    void budgetIsExclusiveUpperBound() {
        int floor = TrainTickEvents.breakFloorY(geometry());

        assertTrue(TrainTickEvents.shouldBreak(true, true, TRAIN_Y, floor, 0, 256), "first break");
        assertTrue(TrainTickEvents.shouldBreak(true, true, TRAIN_Y, floor, 255, 256), "last break under cap");
        assertFalse(TrainTickEvents.shouldBreak(true, true, TRAIN_Y, floor, 256, 256), "cap reached");
        assertFalse(TrainTickEvents.shouldBreak(true, true, TRAIN_Y, floor, 999, 256), "cap overshot");
    }

    @Test
    @DisplayName("throttle fires on the very first call — no Long.MIN_VALUE overflow")
    void throttleFiresFirstTime() {
        // The regression this pins: `now - Long.MIN_VALUE` overflows to a large NEGATIVE value,
        // which is always below the threshold, so the naive arithmetic suppressed the line forever.
        // 36 blocks were broken in-game with zero log lines emitted before this was caught.
        assertFalse(TrainTickEvents.throttled(Long.MIN_VALUE, 0L), "first call at game time 0");
        assertFalse(TrainTickEvents.throttled(Long.MIN_VALUE, 12_345L), "first call mid-session");

        assertTrue(TrainTickEvents.throttled(100L, 100L), "same tick");
        assertTrue(TrainTickEvents.throttled(100L, 119L), "one tick under the window");
        assertFalse(TrainTickEvents.throttled(100L, 120L), "window elapsed");
        assertFalse(TrainTickEvents.throttled(100L, 5_000L), "long since");
        // Game time running backwards (world reload) must not wedge the throttle shut.
        assertFalse(TrainTickEvents.throttled(5_000L, 10L), "clock went backwards");
    }

    @Test
    @DisplayName("canBreakAt decides the cheap half alone, so collide is never computed needlessly")
    void cheapGateStandsAlone() {
        int floor = TrainTickEvents.breakFloorY(geometry());

        // Each of the three cheap refusals must be decidable WITHOUT collide — that is the whole
        // point of the split: establishing collide costs a collision-shape query plus a Sable
        // sub-level lookup, and must not be paid for a cell that can never break.
        assertFalse(TrainTickEvents.canBreakAt(false, TRAIN_Y, floor, 0, 256), "feature off");
        assertFalse(TrainTickEvents.canBreakAt(true, TRAIN_Y - 1, floor, 0, 256), "below rail floor");
        assertFalse(TrainTickEvents.canBreakAt(true, TRAIN_Y, floor, 256, 256), "budget spent");
        assertTrue(TrainTickEvents.canBreakAt(true, TRAIN_Y, floor, 0, 256), "breakable candidate");

        // shouldBreak stays exactly canBreakAt AND collide, so the reorder changed no behaviour.
        assertFalse(TrainTickEvents.shouldBreak(true, false, TRAIN_Y, floor, 0, 256), "no collision");
        assertTrue(TrainTickEvents.shouldBreak(true, true, TRAIN_Y, floor, 0, 256), "collision");
    }

    @Test
    @DisplayName("a fully-spent shared budget (zero remaining) refuses immediately")
    void exhaustedSharedBudgetRefuses() {
        // The call site passes MAX - alreadyBroken as the budget, so a later train in the same tick
        // can legitimately receive 0 (or, if a train overshot, a negative remainder).
        int floor = TrainTickEvents.breakFloorY(geometry());

        assertFalse(TrainTickEvents.shouldBreak(true, true, TRAIN_Y, floor, 0, 0));
        assertFalse(TrainTickEvents.shouldBreak(true, true, TRAIN_Y, floor, 0, -5));
    }
}
