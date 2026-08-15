package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The counter a train stands still by.
 *
 * <p>What it has to get right is narrow but unforgiving: the count feeds straight into the elapsed
 * term of the position formula, so a tick counted twice moves every carriage of that train, and a
 * count that survives the train it described moves a different train entirely.</p>
 */
class TrainMotionFreezeTest {

    private static final UUID TRAIN = UUID.nameUUIDFromBytes("train-a".getBytes());
    private static final UUID OTHER = UUID.nameUUIDFromBytes("train-b".getBytes());

    @BeforeEach
    @AfterEach
    void reset() {
        TrainMotionFreeze.clear();
    }

    @Test
    @DisplayName("a train nobody has ever stopped costs nothing")
    void unknownTrainIsZero() {
        assertFalse(TrainMotionFreeze.isFrozen(TRAIN));
        assertEquals(0L, TrainMotionFreeze.frozenTicks(TRAIN));
    }

    @Test
    @DisplayName("null is answered rather than thrown at — a carriage with no train id must still tick")
    void nullTrainIsSafe() {
        assertFalse(TrainMotionFreeze.isFrozen(null));
        assertEquals(0L, TrainMotionFreeze.frozenTicks(null));
        TrainMotionFreeze.setFrozen(null, true);
        TrainMotionFreeze.tickFrozen();
        assertEquals(0L, TrainMotionFreeze.frozenTicks(null));
    }

    @Test
    @DisplayName("only frozen trains accumulate, one tick per tick")
    void countsWhileFrozen() {
        TrainMotionFreeze.setFrozen(TRAIN, true);
        TrainMotionFreeze.tickFrozen();
        TrainMotionFreeze.tickFrozen();
        TrainMotionFreeze.tickFrozen();

        assertEquals(3L, TrainMotionFreeze.frozenTicks(TRAIN));
        assertEquals(0L, TrainMotionFreeze.frozenTicks(OTHER),
            "a second train with nobody in its rooms must keep running");
    }

    @Test
    @DisplayName("releasing stops the count where it was and leaves it there")
    void stopsCountingWhenReleased() {
        TrainMotionFreeze.setFrozen(TRAIN, true);
        TrainMotionFreeze.tickFrozen();
        TrainMotionFreeze.tickFrozen();
        TrainMotionFreeze.setFrozen(TRAIN, false);
        TrainMotionFreeze.tickFrozen();
        TrainMotionFreeze.tickFrozen();

        // The two ticks after release are travel, not standing still. Keeping the
        // total is what makes resuming seamless: it stays subtracted, so the
        // carriage carries on from where it stopped instead of jumping.
        assertEquals(2L, TrainMotionFreeze.frozenTicks(TRAIN));
        assertFalse(TrainMotionFreeze.isFrozen(TRAIN));
    }

    @Test
    @DisplayName("freezing again adds to the total rather than restarting it")
    void secondFreezeAccumulates() {
        TrainMotionFreeze.setFrozen(TRAIN, true);
        TrainMotionFreeze.tickFrozen();
        TrainMotionFreeze.setFrozen(TRAIN, false);
        TrainMotionFreeze.tickFrozen();
        TrainMotionFreeze.setFrozen(TRAIN, true);
        TrainMotionFreeze.tickFrozen();

        assertEquals(2L, TrainMotionFreeze.frozenTicks(TRAIN));
    }

    @Test
    @DisplayName("re-stating the same verdict every tick does not double-count")
    void repeatedVerdictIsIdempotent() {
        for (int i = 0; i < 5; i++) {
            TrainMotionFreeze.setFrozen(TRAIN, true);
        }
        TrainMotionFreeze.tickFrozen();
        assertEquals(1L, TrainMotionFreeze.frozenTicks(TRAIN));
    }

    @Test
    @DisplayName("thawAll releases every train but keeps what they accumulated")
    void thawKeepsTheCounts() {
        TrainMotionFreeze.setFrozen(TRAIN, true);
        TrainMotionFreeze.setFrozen(OTHER, true);
        TrainMotionFreeze.tickFrozen();
        TrainMotionFreeze.tickFrozen();

        TrainMotionFreeze.thawAll();

        assertFalse(TrainMotionFreeze.isFrozen(TRAIN));
        assertFalse(TrainMotionFreeze.isFrozen(OTHER));
        // Dropping the counts here would take every historical frozen tick back
        // out of the subtraction at once and lurch both trains forward by the
        // whole time they had spent stopped.
        assertEquals(2L, TrainMotionFreeze.frozenTicks(TRAIN));
        assertEquals(2L, TrainMotionFreeze.frozenTicks(OTHER));

        TrainMotionFreeze.tickFrozen();
        assertEquals(2L, TrainMotionFreeze.frozenTicks(TRAIN), "a released train stops counting");
    }

    @Test
    @DisplayName("clear forgets the counts — a train id means a different train next world")
    void clearForgetsEverything() {
        TrainMotionFreeze.setFrozen(TRAIN, true);
        TrainMotionFreeze.tickFrozen();
        TrainMotionFreeze.clear();

        assertFalse(TrainMotionFreeze.isFrozen(TRAIN));
        assertEquals(0L, TrainMotionFreeze.frozenTicks(TRAIN));
    }

    @Test
    @DisplayName("ticking with nothing frozen is a no-op")
    void tickWithNothingFrozen() {
        TrainMotionFreeze.tickFrozen();
        assertEquals(0L, TrainMotionFreeze.frozenTicks(TRAIN));
        assertTrue(TrainMotionFreeze.frozenTicks(OTHER) == 0L);
    }
}
