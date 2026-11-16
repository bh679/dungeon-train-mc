package games.brennan.dungeontrain.client.tributeroom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TributeArrivalFovTest {

    @AfterEach
    void reset() {
        TributeArrivalFov.reset();
    }

    @Test
    void noEaseMeansTheGamesOwnFov() {
        assertEquals(1.0, TributeArrivalFov.progress(123L), 1e-9);
        assertEquals(70.0, TributeArrivalFov.fovAt(1.0, 70.0), 1e-9);
    }

    @Test
    void startsNarrowAndEasesOutSmoothly() {
        TributeArrivalFov.begin(1000L, 50.0);
        assertEquals(0.0, TributeArrivalFov.progress(1000L), 1e-9);
        assertEquals(50.0, TributeArrivalFov.fovAt(TributeArrivalFov.progress(1000L), 70.0), 1e-9);
        double mid = TributeArrivalFov.progress(1000L + TributeArrivalFov.EASE_MS / 2);
        assertEquals(0.5, mid, 1e-9, "smoothstep is symmetric");
        assertEquals(1.0, TributeArrivalFov.progress(1000L + TributeArrivalFov.EASE_MS), 1e-9);
        assertEquals(70.0, TributeArrivalFov.fovAt(1.0, 70.0), 1e-9);
        double quarter = TributeArrivalFov.progress(1000L + TributeArrivalFov.EASE_MS / 4);
        assertTrue(quarter < 0.25, "eases in: slower than linear at the start");
    }

    @Test
    void aBadStartFallsBack() {
        TributeArrivalFov.begin(1000L, Double.NaN);
        assertEquals(TributeArrivalFov.FALLBACK_START_FOV, TributeArrivalFov.fovAt(0.0, 70.0), 1e-9);
    }

    @Test
    void fillFovFitsThePicturesHeight() {
        // A 16:9 picture is wider than the 5 x 3 face inside its bezel, so its width fills and its height follows.
        double innerW = 5 - 2 * TvPointer.BEZEL;
        double pictureH = innerW / (16.0 / 9.0);
        double expected = Math.toDegrees(2 * Math.atan(pictureH / 2 / 2.5));
        assertEquals(expected, TributeArrivalFov.fillFov(5, 3, 16.0 / 9.0, 2.5), 1e-9);
        // A 4:3 picture is narrower than the face: its height is the face's inner height.
        double tall = Math.toDegrees(2 * Math.atan((3 - 2 * TvPointer.BEZEL) / 2 / 2.5));
        assertEquals(tall, TributeArrivalFov.fillFov(5, 3, 4.0 / 3.0, 2.5), 1e-9);
    }

    @Test
    void theRoomsTvFillsNarrowerThanTheUsualFov() {
        // From the stand point the zoom out must ease out to a 70-degree game, not in.
        double fov = TributeArrivalFov.fillFov(5, 3, 16.0 / 9.0, 2.5 + 0.2);
        assertTrue(fov < 70.0, "fill fov " + fov);
        assertTrue(fov > 40.0, "fill fov " + fov);
    }
}
