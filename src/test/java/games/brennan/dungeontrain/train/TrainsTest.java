package games.brennan.dungeontrain.train;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure-geometry tests for the "on the train" containment envelope shared by boarding
 * detection ({@code BoardingProgressEvents}) and the resume re-anchor ({@code ResumeWatchdog})
 * — see {@link Trains#withinOnTrainEnvelope}. Sample carriage worldAABB: x[0..10] y[64..67]
 * z[0..5], with a 1-block horizontal pad (joint bridging) and a +3 roof pad (sprint-jumpers),
 * no bottom pad.
 */
class TrainsTest {

    private static final double MINX = 0, MINY = 64, MINZ = 0;
    private static final double MAXX = 10, MAXY = 67, MAXZ = 5;

    private static boolean on(double px, double py, double pz) {
        return Trains.withinOnTrainEnvelope(MINX, MINY, MINZ, MAXX, MAXY, MAXZ, px, py, pz);
    }

    @Test
    void standingOnDeckIsOnTrain() {
        assertTrue(on(5, 67, 2));    // on the top face
        assertTrue(on(0, 64, 0));    // min corner
        assertTrue(on(10, 67, 5));   // max corner
    }

    @Test
    void horizontalPadBridgesJoints() {
        assertTrue(on(-1.0, 65, 2));    // exactly one block before minX (group joint)
        assertTrue(on(11.0, 65, 2));    // exactly one block past maxX
        assertTrue(on(2, 65, -1.0));    // one block before minZ
        assertFalse(on(-1.01, 65, 2));  // just beyond the horizontal pad
        assertFalse(on(11.01, 65, 2));
        assertFalse(on(2, 65, 6.01));
    }

    @Test
    void roofPadCountsJumpers() {
        assertTrue(on(5, 70.0, 2));     // maxY + 3 — sprint-jump peak still "on train"
        assertFalse(on(5, 70.01, 2));   // above the roof pad
    }

    @Test
    void belowDeckFloorIsOff() {
        assertFalse(on(5, 63.99, 2));   // below minY — no bottom pad
    }

    @Test
    void farAwayIsOff() {
        assertFalse(on(150, 65, 2));    // the ~150-block post-resume separation (#547)
        assertFalse(on(5, 65, 50));
    }
}
