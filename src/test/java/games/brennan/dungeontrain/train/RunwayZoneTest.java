package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kill-ahead runway geometry. The train box and mob positions are the ones a headless dedicated
 * server recorded for the walked-off-mob bug (2026-10-20): a train running +X at 2 blocks/s, box
 * x 105.7..703.7, y 78..85, z 0..7 (deck surface y 79), a cow being 0.9 x 1.4.
 */
class RunwayZoneTest {

    private static final double LOOK = 8;
    private static final double MIN_X = 105.7, MIN_Y = 78, MIN_Z = 0;
    private static final double MAX_X = 703.7, MAX_Y = 85, MAX_Z = 7;
    private static final double MOB_HEIGHT = 1.4;

    /** Train moving +X; mob centre (x, z), feet at y. */
    private static boolean plusX(double x, double y, double z) {
        return RunwayZone.isInRunway(MIN_X, MIN_Y, MIN_Z, MAX_X, MAX_Y, MAX_Z,
            LOOK, 1, 0, 0, x, y, z, y, y + MOB_HEIGHT);
    }

    @Test
    void mobOnTheTrackBedUnderTheTrainIsSpared() {
        // The recorded victim: feet y=77 (a block under the floor), centre inside the train's span.
        assertFalse(plusX(370.2, 77.0, 3.5));
    }

    @Test
    void mobOnTheGroundRightBesideTheCarriageIsSpared() {
        // Walked off the side; feet 77.82, centre z 6.72 — inside the box's lateral span, behind the face.
        assertFalse(plusX(398.73, 77.82, 6.72));
    }

    @Test
    void mobAboardIsSpared() {
        assertFalse(plusX(400, 79, 3.5));
    }

    @Test
    void mobOnTheTrackAheadOfTheLeadingFaceIsInTheRunway() {
        assertTrue(plusX(MAX_X + 3, 77.0, 3.5));
    }

    @Test
    void mobAtDeckHeightAheadIsInTheRunway() {
        assertTrue(plusX(MAX_X + 0.5, 79, 3.5));
    }

    @Test
    void runwayEndsAtTheLookahead() {
        assertTrue(plusX(MAX_X + LOOK, 79, 3.5));
        assertFalse(plusX(MAX_X + LOOK + 0.5, 79, 3.5));
    }

    @Test
    void mobBesideTheTrackAheadIsSpared() {
        // Ahead of the face but its centre is outside the train's lateral span.
        assertFalse(plusX(MAX_X + 3, 77, MAX_Z + 0.4));
        assertFalse(plusX(MAX_X + 3, 77, MIN_Z - 0.4));
    }

    @Test
    void mobBehindTheTrainIsSpared() {
        assertFalse(plusX(MIN_X - 3, 79, 3.5));
    }

    @Test
    void mobWellBelowOrAboveTheTrainAheadIsSpared() {
        assertFalse(plusX(MAX_X + 3, MIN_Y - MOB_HEIGHT - 0.1, 3.5));
        assertFalse(plusX(MAX_X + 3, MAX_Y + 0.1, 3.5));
    }

    @Test
    void reversedTrainRunsTheOtherWay() {
        assertTrue(RunwayZone.isInRunway(MIN_X, MIN_Y, MIN_Z, MAX_X, MAX_Y, MAX_Z,
            LOOK, -1, 0, 0, MIN_X - 3, 79, 3.5, 79, 79 + MOB_HEIGHT));
        assertFalse(RunwayZone.isInRunway(MIN_X, MIN_Y, MIN_Z, MAX_X, MAX_Y, MAX_Z,
            LOOK, -1, 0, 0, MAX_X + 3, 79, 3.5, 79, 79 + MOB_HEIGHT));
    }

    @Test
    void idleTrainHasNoRunway() {
        assertFalse(RunwayZone.isInRunway(MIN_X, MIN_Y, MIN_Z, MAX_X, MAX_Y, MAX_Z,
            LOOK, 0, 0, 0, MAX_X + 3, 79, 3.5, 79, 79 + MOB_HEIGHT));
    }
}
