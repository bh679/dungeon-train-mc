package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A carriage box x 100..137, y 78..85, z 0..7 (floor block y 78, deck surface y 79) and an
 * Enderman (2.9 tall, eyes 2.55 up, 0.6 wide) standing on the track bed under it.
 */
class WedgedUnderFloorTest {

    private static final double MIN_X = 100, MIN_Y = 78, MIN_Z = 0;
    private static final double MAX_X = 137, MAX_Z = 7;
    private static final double ENDERMAN_EYE = 2.55;
    private static final double SPIDER_EYE = 0.65;
    private static final double ZOMBIFIED_PIGLIN_EYE = 1.79;

    private static boolean wedged(double x, double feetY, double z) {
        return wedged(x, feetY, z, ENDERMAN_EYE);
    }

    private static boolean wedged(double x, double feetY, double z, double eyeHeight) {
        return WedgedUnderFloor.isWedged(x, feetY, z, feetY + eyeHeight,
            MIN_X, MIN_Y, MIN_Z, MAX_X, MAX_Z);
    }

    @Test
    void standingOnTrackBedUnderCarriageIsWedged() {
        assertTrue(wedged(110, 78, 3.5));
    }

    @Test
    void sunkLowerWithOnlyTheHeadThroughIsWedged() {
        assertTrue(wedged(110, 76.5, 3.5));
    }

    @Test
    void standingOnTheDeckIsNotWedged() {
        assertFalse(wedged(110, 79, 3.5));
        assertFalse(wedged(110, 78.6, 3.5)); // carry slop just under the deck surface
    }

    @Test
    void besideOrAheadOfTheCarriageIsNotWedged() {
        assertFalse(wedged(110, 78, -0.5));
        assertFalse(wedged(110, 78, 7.5));
        assertFalse(wedged(140, 78, 3.5));
    }

    @Test
    void tooLowToReachTheFloorIsNotWedged() {
        assertFalse(wedged(110, 74, 3.5)); // eyes at 76.55, under the floor block
    }

    @Test
    void zombifiedPiglinOnTrackBedIsWedged() {
        assertTrue(wedged(110, 77, 0.4, ZOMBIFIED_PIGLIN_EYE)); // eyes at 78.79, in the floor
    }

    @Test
    void spiderBesideTheTrackWithItsBackGrazingTheFloorIsNotWedged() {
        // The 0.1407.0 loop: feet 77.1875, box top 78.09 (0.09 into the floor), eyes 77.84, centre
        // just under the floor's edge as it walks at a rider. Nothing of it shows on the deck.
        assertFalse(wedged(110, 77.1875, 0.1, SPIDER_EYE));
        assertFalse(wedged(110, 77.1875, 6.9, SPIDER_EYE));
    }

    @Test
    void movingAlongXSendsItOutAcrossZNearerSideFirst() {
        double[] t = WedgedUnderFloor.sideTargets(110, 2, 0.3, MIN_X, MIN_Z, MAX_X, MAX_Z, 2, 0);
        double out = 0.3 + WedgedUnderFloor.SIDE_CLEARANCE;
        assertArrayEquals(new double[] {110, MIN_Z - out, 110, MAX_Z + out}, t, 1e-9);

        double[] far = WedgedUnderFloor.sideTargets(110, 6, 0.3, MIN_X, MIN_Z, MAX_X, MAX_Z, -2, 0);
        assertArrayEquals(new double[] {110, MAX_Z + out, 110, MIN_Z - out}, far, 1e-9);
    }

    @Test
    void movingAlongZSendsItOutAcrossX() {
        double[] t = WedgedUnderFloor.sideTargets(101, 3, 0.3, MIN_X, MIN_Z, MAX_X, MAX_Z, 0, 2);
        double out = 0.3 + WedgedUnderFloor.SIDE_CLEARANCE;
        assertArrayEquals(new double[] {MIN_X - out, 3, MAX_X + out, 3}, t, 1e-9);
    }

    @Test
    void idleCarriageUsesTheNearerEdge() {
        // 2 from the low-Z edge, 10 from the low-X edge: out across Z.
        double[] t = WedgedUnderFloor.sideTargets(110, 2, 0.3, MIN_X, MIN_Z, MAX_X, MAX_Z, 0, 0);
        assertTrue(t[1] < MIN_Z);
    }
}
