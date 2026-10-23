package games.brennan.dungeontrain.wind;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The ground lee test: a wall upwind shelters at foot or eye height; downwind or too far away it doesn't. A
 * single block is enough, at any wind angle, as long as it is in the wind's path to the body.
 */
final class GroundExposureTest {

    private static final double EYE = 1.62;
    /** Wind blows toward +X, so upwind is -X. */
    private static final double DIR_X = 1.0;
    private static final double DIR_Z = 0.0;
    /** A player's half width. */
    private static final double HALF = 0.3;

    private static GroundExposure.Solidity wallAt(int wx, int minY, int maxY) {
        return (x, y, z) -> Math.floor(x) == wx && y >= minY && y < maxY + 1;
    }

    @Test
    @DisplayName("open ground is exposed")
    void open() {
        assertEquals(TrainExposure.Result.EXPOSED,
                GroundExposure.classify((x, y, z) -> false, 0.5, 64, 0.5, EYE, HALF, DIR_X, DIR_Z));
    }

    @Test
    @DisplayName("a two-high wall three blocks upwind is a lee")
    void wallUpwind() {
        assertEquals(TrainExposure.Result.IN_LEE,
                GroundExposure.classify(wallAt(-3, 64, 65), 0.5, 64, 0.5, EYE, HALF, DIR_X, DIR_Z));
    }

    @Test
    @DisplayName("a block at eye height alone still shelters")
    void eyeHeightOnly() {
        assertEquals(TrainExposure.Result.IN_LEE,
                GroundExposure.classify(wallAt(-2, 65, 65), 0.5, 64, 0.5, EYE, HALF, DIR_X, DIR_Z));
    }

    @Test
    @DisplayName("the same wall downwind doesn't shelter")
    void wallDownwind() {
        assertEquals(TrainExposure.Result.EXPOSED,
                GroundExposure.classify(wallAt(3, 64, 65), 0.5, 64, 0.5, EYE, HALF, DIR_X, DIR_Z));
    }

    @Test
    @DisplayName("a wall past the lee scan doesn't shelter")
    void wallTooFar() {
        assertEquals(TrainExposure.Result.EXPOSED,
                GroundExposure.classify(wallAt(-(TrainExposure.LEE_SCAN + 1), 64, 65), 0.5, 64, 0.5, EYE, HALF, DIR_X, DIR_Z));
    }

    @Test
    @DisplayName("a wall at the edge of the lee scan shelters")
    void wallAtScanEdge() {
        assertEquals(TrainExposure.Result.IN_LEE,
                GroundExposure.classify(wallAt(-TrainExposure.LEE_SCAN, 64, 65), 0.5, 64, 0.5, EYE, HALF, DIR_X, DIR_Z));
    }

    @Test
    @DisplayName("calm (zero heading) is exposed")
    void noHeading() {
        assertEquals(TrainExposure.Result.EXPOSED,
                GroundExposure.classify(wallAt(-1, 64, 65), 0.5, 64, 0.5, EYE, HALF, 0.0, 0.0));
    }

    // ---- a single block ---------------------------------------------------------------------------

    private static GroundExposure.Solidity blockAt(int bx, int by, int bz) {
        return (x, y, z) -> Math.floor(x) == bx && Math.floor(y) == by && Math.floor(z) == bz;
    }

    private static TrainExposure.Result atHeading(GroundExposure.Solidity probe, double x, double z, double deg) {
        double r = Math.toRadians(deg);
        return GroundExposure.classify(probe, x, 64, z, EYE, HALF, Math.cos(r), Math.sin(r));
    }

    @Test
    @DisplayName("one block at the feet, right upwind, shelters")
    void singleBlockFeet() {
        assertEquals(TrainExposure.Result.IN_LEE,
                GroundExposure.classify(blockAt(-1, 64, 0), 0.5, 64, 0.5, EYE, HALF, DIR_X, DIR_Z));
    }

    @Test
    @DisplayName("one block at head height, right upwind, shelters")
    void singleBlockHead() {
        assertEquals(TrainExposure.Result.IN_LEE,
                GroundExposure.classify(blockAt(-1, 65, 0), 0.5, 64, 0.5, EYE, HALF, DIR_X, DIR_Z));
    }

    @Test
    @DisplayName("one block above the head doesn't shelter")
    void singleBlockAboveHead() {
        assertEquals(TrainExposure.Result.EXPOSED,
                GroundExposure.classify(blockAt(-1, 66, 0), 0.5, 64, 0.5, EYE, HALF, DIR_X, DIR_Z));
    }

    @Test
    @DisplayName("one block next to you shelters at every diagonal it faces")
    void singleBlockDiagonals() {
        // Wind toward +Z (the default heading): the block on the -Z side. Veered either way by 45°, still there.
        for (double deg : new double[] {90, 45, 135, 60, 120}) {
            assertEquals(TrainExposure.Result.IN_LEE, atHeading(blockAt(0, 64, -1), 0.5, 0.5, deg),
                    "heading " + deg);
        }
        // Wind from the -X side, veered.
        for (double deg : new double[] {0, 300, 30, 330}) {
            assertEquals(TrainExposure.Result.IN_LEE, atHeading(blockAt(-1, 65, 0), 0.5, 0.5, deg),
                    "heading " + deg);
        }
    }

    @Test
    @DisplayName("a block a few away on a diagonal wind is found, not skipped at a corner")
    void diagonalFarBlock() {
        // From (0.5, 0.5) at 45° upwind the ray runs through the corners of (-1,-1), (-2,-2)… and the cells
        // either side of them; the side rays cover the cell beside the corner.
        assertEquals(TrainExposure.Result.IN_LEE, atHeading(blockAt(-3, 64, -2), 0.5, 0.5, 45));
    }

    @Test
    @DisplayName("standing off to one side, a block in front of your edge still shelters")
    void offCentre() {
        // Wind toward +Z. Centre at x = 1.1 is over column 1; the body's -X side reaches column 0, where the
        // block is. Mirrored: centre over column 0, +X side over column 1.
        assertEquals(TrainExposure.Result.IN_LEE, atHeading(blockAt(0, 64, -1), 1.1, 0.5, 90));
        assertEquals(TrainExposure.Result.IN_LEE, atHeading(blockAt(1, 65, -1), 0.9, 0.5, 90));
    }

    @Test
    @DisplayName("one block beside you or downwind doesn't shelter")
    void singleBlockNotUpwind() {
        assertEquals(TrainExposure.Result.EXPOSED, atHeading(blockAt(-1, 64, 0), 0.5, 0.5, 90));
        assertEquals(TrainExposure.Result.EXPOSED, atHeading(blockAt(1, 64, 0), 0.5, 0.5, 90));
        assertEquals(TrainExposure.Result.EXPOSED, atHeading(blockAt(0, 64, 1), 0.5, 0.5, 90));
    }

    @Test
    @DisplayName("a wall flush beside you, running with the wind, doesn't shelter")
    void flushSideWall() {
        // Body edge exactly on x = 1.0; the wall fills x = 1 for every z.
        GroundExposure.Solidity wall = (x, y, z) -> Math.floor(x) == 1;
        assertEquals(TrainExposure.Result.EXPOSED, atHeading(wall, 0.7, 0.5, 90));
    }

    @Test
    @DisplayName("the block you stand in doesn't count")
    void ownBlock() {
        assertEquals(TrainExposure.Result.EXPOSED, atHeading(blockAt(0, 64, 0), 0.5, 0.5, 90));
    }
}
