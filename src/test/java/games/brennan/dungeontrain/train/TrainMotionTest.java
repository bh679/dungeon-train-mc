package games.brennan.dungeontrain.train;

import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The train-level velocity schedule. Pure bookkeeping — no Minecraft bootstrap. Every test uses its
 * own random train id, so the shared static map needs no reset between them.
 */
final class TrainMotionTest {

    private static final double DT = 1.0 / 20.0;
    private static final Vector3d TWO = new Vector3d(2, 0, 0);
    private static final Vector3d FOUR = new Vector3d(4, 0, 0);
    private static final Vector3d ZERO = new Vector3d(0, 0, 0);

    @Test
    @DisplayName("a train that has never changed speed has no schedule and answers the fallback")
    void unknownTrain_isFree() {
        UUID train = UUID.randomUUID();
        assertEquals(0, TrainMotion.epoch(train));
        assertTrue(TrainMotion.changesSince(train, 0).isEmpty());
        assertEquals(TWO, TrainMotion.velocityOr(train, TWO));
        assertNull(TrainMotion.velocityOr(train, null));
        assertEquals(0, TrainMotion.epoch(null));
        assertTrue(TrainMotion.changesSince(null, 0).isEmpty());
    }

    @Test
    @DisplayName("a real change is recorded with its tick; re-applying the same speed is not a change")
    void setVelocity_recordsOnlyRealChanges() {
        UUID train = UUID.randomUUID();

        assertFalse(TrainMotion.setVelocity(train, TWO, new Vector3d(TWO), 100L)); // same as before
        assertEquals(0, TrainMotion.epoch(train));

        assertTrue(TrainMotion.setVelocity(train, TWO, FOUR, 100L));
        assertEquals(1, TrainMotion.epoch(train));
        assertEquals(FOUR, TrainMotion.velocityOr(train, TWO));

        // The caller's idea of "previous" stops mattering once the schedule has an entry: a stale
        // carriage still saying 2 must not make a second write of 4 look like a change.
        assertFalse(TrainMotion.setVelocity(train, TWO, new Vector3d(FOUR), 150L));
        assertEquals(1, TrainMotion.epoch(train));

        assertTrue(TrainMotion.setVelocity(train, TWO, ZERO, 200L));
        List<TrainMotion.Change> all = TrainMotion.changesSince(train, 0);
        assertEquals(2, all.size());
        assertEquals(100L, all.get(0).gameTick());
        assertEquals(200L, all.get(1).gameTick());
        assertEquals(ZERO, all.get(1).velocity());
    }

    @Test
    @DisplayName("a train nobody can vouch for still records the change")
    void setVelocity_unknownPrevious_records() {
        UUID train = UUID.randomUUID();
        assertTrue(TrainMotion.setVelocity(train, null, FOUR, 10L));
        assertEquals(1, TrainMotion.epoch(train));
    }

    @Test
    @DisplayName("the recorded velocity is a copy: mutating the caller's vector changes nothing")
    void setVelocity_copiesTheVector() {
        UUID train = UUID.randomUUID();
        Vector3d mine = new Vector3d(4, 0, 0);
        TrainMotion.setVelocity(train, TWO, mine, 10L);
        mine.set(99, 0, 0);
        assertEquals(4.0, TrainMotion.velocityOr(train, TWO).x());
    }

    @Test
    @DisplayName("changesSince hands a carriage exactly what it has not applied")
    void changesSince_slicesByEpoch() {
        UUID train = UUID.randomUUID();
        TrainMotion.setVelocity(train, TWO, FOUR, 100L);
        TrainMotion.setVelocity(train, TWO, ZERO, 160L);
        TrainMotion.setVelocity(train, TWO, TWO, 220L);

        assertEquals(3, TrainMotion.changesSince(train, 0).size());
        assertEquals(160L, TrainMotion.changesSince(train, 1).get(0).gameTick());
        assertEquals(1, TrainMotion.changesSince(train, 2).size());
        assertTrue(TrainMotion.changesSince(train, 3).isEmpty());
        assertTrue(TrainMotion.changesSince(train, 99).isEmpty());
    }

    @Test
    @DisplayName("a change can never predate the one before it")
    void setVelocity_clockNeverRunsBackwards() {
        UUID train = UUID.randomUUID();
        TrainMotion.setVelocity(train, TWO, FOUR, 500L);
        TrainMotion.setVelocity(train, TWO, ZERO, 400L);
        assertEquals(500L, TrainMotion.changesSince(train, 1).get(0).gameTick());
    }

    @Test
    @DisplayName("travelX with no changes is travelDistance, to the bit")
    void travelX_noChanges_isTheOldArithmetic() {
        assertEquals(TrainTransformProvider.travelDistance(2.0, 100L),
            TrainMotion.travelX(2.0, 1000L, 10L, List.of(), 1100L, 10L));
        assertEquals(TrainTransformProvider.travelDistance(2.0, 80L),
            TrainMotion.travelX(2.0, 1000L, 10L, List.of(), 1100L, 30L)); // 20 frozen
        assertEquals(0.0, TrainMotion.travelX(2.0, 1000L, 10L, List.of(), 900L, 10L));  // clock behind
        assertEquals(0.0, TrainMotion.travelX(2.0, 1000L, 10L, List.of(), 1100L, 500L)); // frozen throughout
    }

    @Test
    @DisplayName("travelX prices each stretch at the speed it was travelled at")
    void travelX_piecewise() {
        List<TrainMotion.Change> changes = List.of(
            new TrainMotion.Change(1040L, 0L, FOUR),
            new TrainMotion.Change(1070L, 0L, ZERO));
        // 40 ticks at 2, 30 at 4, 30 at 0.
        assertEquals((2.0 * 40 + 4.0 * 30) * DT,
            TrainMotion.travelX(2.0, 1000L, 0L, changes, 1100L, 0L), 1e-9);
        // Asked about a moment before the second change: it has not happened yet.
        assertEquals((2.0 * 40 + 4.0 * 10) * DT,
            TrainMotion.travelX(2.0, 1000L, 0L, changes, 1050L, 0L), 1e-9);
        // A change dated at or before the start only says what the speed already is.
        assertEquals(4.0 * 50 * DT,
            TrainMotion.travelX(2.0, 1040L, 0L, changes.subList(0, 1), 1090L, 0L), 1e-9);
    }

    @Test
    @DisplayName("travelX takes frozen ticks out of the stretch they fell in")
    void travelX_frozenPerStretch() {
        // 10 ticks frozen before the change (at speed 2), 5 after it (at speed 4).
        List<TrainMotion.Change> changes = List.of(new TrainMotion.Change(1040L, 110L, FOUR));
        assertEquals((2.0 * (40 - 10) + 4.0 * (60 - 5)) * DT,
            TrainMotion.travelX(2.0, 1000L, 100L, changes, 1100L, 115L), 1e-9);
    }

    @Test
    @DisplayName("clear forgets every train")
    void clear_forgets() {
        UUID train = UUID.randomUUID();
        TrainMotion.setVelocity(train, TWO, FOUR, 100L);
        TrainMotion.clear();
        assertEquals(0, TrainMotion.epoch(train));
        assertEquals(TWO, TrainMotion.velocityOr(train, TWO));
    }
}
