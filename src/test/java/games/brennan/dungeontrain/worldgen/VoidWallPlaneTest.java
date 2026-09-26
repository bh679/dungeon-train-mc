package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link VoidWallPlane}: hides boxes wholly past the wall, never the near side or the track corridor. */
final class VoidWallPlaneTest {

    private static final int TRAIN_Y = 100;
    private static final VoidWallPlane WALL = VoidWallPlane.at(1000, TRAIN_Y, 7);

    @Test
    @DisplayName("a chunk section wholly past the wall and off the track is hidden")
    void hidesPastWall() {
        assertTrue(WALL.hides(1008, 48, 64, 1024, 64, 80));
    }

    @Test
    @DisplayName("a section straddling the wall, or on the near side, is kept")
    void keepsNearSide() {
        assertFalse(WALL.hides(992, 48, 64, 1008, 64, 80));
        assertFalse(WALL.hides(900, 48, 64, 916, 64, 80));
    }

    @Test
    @DisplayName("the section the track runs through is kept past the wall")
    void keepsTrackCorridor() {
        assertFalse(WALL.hides(2000, 96, 0, 2016, 112, 16));
    }

    @Test
    @DisplayName("a section beside the track at the same height is hidden")
    void hidesBesideTrack() {
        assertTrue(WALL.hides(2000, 96, 16, 2016, 112, 32));
        assertTrue(WALL.hides(2000, 96, -16, 2016, 112, 0));
    }

    @Test
    @DisplayName("an infinite wall is no wall")
    void infiniteIsNone() {
        assertFalse(VoidWallPlane.at(Double.POSITIVE_INFINITY, TRAIN_Y, 7).active());
        assertFalse(VoidWallPlane.NONE.hides(1e9, 0, 50, 1e9 + 16, 16, 66));
    }
}
