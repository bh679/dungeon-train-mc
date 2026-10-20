package games.brennan.dungeontrain.wind;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The ground lee test: a wall upwind shelters at foot or eye height; downwind or too far away it doesn't. */
final class GroundExposureTest {

    private static final double EYE = 1.62;
    /** Wind blows toward +X, so upwind is -X. */
    private static final double DIR_X = 1.0;
    private static final double DIR_Z = 0.0;

    private static GroundExposure.Solidity wallAt(int wx, int minY, int maxY) {
        return (x, y, z) -> Math.floor(x) == wx && y >= minY && y < maxY + 1;
    }

    @Test
    @DisplayName("open ground is exposed")
    void open() {
        assertEquals(TrainExposure.Result.EXPOSED,
                GroundExposure.classify((x, y, z) -> false, 0.5, 64, 0.5, EYE, DIR_X, DIR_Z));
    }

    @Test
    @DisplayName("a two-high wall three blocks upwind is a lee")
    void wallUpwind() {
        assertEquals(TrainExposure.Result.IN_LEE,
                GroundExposure.classify(wallAt(-3, 64, 65), 0.5, 64, 0.5, EYE, DIR_X, DIR_Z));
    }

    @Test
    @DisplayName("a block at eye height alone still shelters")
    void eyeHeightOnly() {
        assertEquals(TrainExposure.Result.IN_LEE,
                GroundExposure.classify(wallAt(-2, 65, 65), 0.5, 64, 0.5, EYE, DIR_X, DIR_Z));
    }

    @Test
    @DisplayName("the same wall downwind doesn't shelter")
    void wallDownwind() {
        assertEquals(TrainExposure.Result.EXPOSED,
                GroundExposure.classify(wallAt(3, 64, 65), 0.5, 64, 0.5, EYE, DIR_X, DIR_Z));
    }

    @Test
    @DisplayName("a wall past the lee scan doesn't shelter")
    void wallTooFar() {
        assertEquals(TrainExposure.Result.EXPOSED,
                GroundExposure.classify(wallAt(-(TrainExposure.LEE_SCAN + 1), 64, 65), 0.5, 64, 0.5, EYE, DIR_X, DIR_Z));
    }

    @Test
    @DisplayName("a wall at the edge of the lee scan shelters")
    void wallAtScanEdge() {
        assertEquals(TrainExposure.Result.IN_LEE,
                GroundExposure.classify(wallAt(-TrainExposure.LEE_SCAN, 64, 65), 0.5, 64, 0.5, EYE, DIR_X, DIR_Z));
    }

    @Test
    @DisplayName("calm (zero heading) is exposed")
    void noHeading() {
        assertEquals(TrainExposure.Result.EXPOSED,
                GroundExposure.classify(wallAt(-1, 64, 65), 0.5, 64, 0.5, EYE, 0.0, 0.0));
    }
}
