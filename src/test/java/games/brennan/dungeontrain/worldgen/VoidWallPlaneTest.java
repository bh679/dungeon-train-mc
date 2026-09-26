package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VoidWallPlane}: terrain reaching past the wall is hidden, straddling included; the camera's
 * side never is; bodies on the track are kept.
 */
final class VoidWallPlaneTest {

    private static final int TRAIN_Y = 100;
    private static final VoidWallPlane WALL = VoidWallPlane.at(1000, Double.NEGATIVE_INFINITY, 700, TRAIN_Y, 7);

    @Test
    @DisplayName("a chunk section wholly past the wall is hidden")
    void hidesPastWall() {
        assertTrue(WALL.hidesTerrain(1008, 1024));
    }

    @Test
    @DisplayName("a section straddling the wall is hidden too — its far part would leak")
    void hidesStraddling() {
        assertTrue(WALL.hidesTerrain(992, 1008));
    }

    @Test
    @DisplayName("a wide DH LOD section straddling the wall is hidden")
    void hidesWideLodStraddling() {
        assertTrue(WALL.hidesTerrain(768, 1280));
    }

    @Test
    @DisplayName("the near side is kept, and so is any box reaching back to the camera")
    void keepsNearSideAndCamera() {
        assertFalse(WALL.hidesTerrain(900, 1000));
        assertFalse(WALL.hidesTerrain(512, 1536));
        assertFalse(WALL.hidesTerrain(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("track sections are hidden like any terrain — the track past the wall is drawn separately")
    void trackSectionIsTerrain() {
        assertTrue(WALL.hidesTerrain(2000, 2016));
    }

    @Test
    @DisplayName("a train carriage on the track past the wall is kept; a body beside the track is not")
    void bodies() {
        assertFalse(WALL.hidesBody(2000, TRAIN_Y - 1, 0, 2009, TRAIN_Y + 6, 7));
        assertFalse(WALL.hidesBody(2000, TRAIN_Y + 6, 2, 2001, TRAIN_Y + 8, 3));   // mob on a carriage roof
        assertTrue(WALL.hidesBody(2000, TRAIN_Y, 40, 2001, TRAIN_Y + 2, 41));
    }

    @Test
    @DisplayName("a wall behind hides what reaches back before it, straddling included, never the camera's side")
    void wallBehind() {
        VoidWallPlane back = VoidWallPlane.at(Double.POSITIVE_INFINITY, 500, 700, TRAIN_Y, 7);
        assertTrue(back.hidesTerrain(400, 416));
        assertTrue(back.hidesTerrain(496, 512));
        assertFalse(back.hidesTerrain(600, 616));
        assertFalse(back.hidesTerrain(256, 768), "reaches the camera");
        assertFalse(back.hidesTerrain(1000, 1016), "ahead, with no wall ahead");
        assertFalse(back.hidesBody(400, TRAIN_Y - 1, 0, 409, TRAIN_Y + 6, 7), "the train's tail on the track");
    }

    @Test
    @DisplayName("an infinite wall is no wall")
    void infiniteIsNone() {
        assertFalse(VoidWallPlane.at(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0, TRAIN_Y, 7).active());
        assertFalse(VoidWallPlane.NONE.hidesTerrain(1e9, 1e9 + 16));
    }
}
