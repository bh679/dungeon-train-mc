package games.brennan.dungeontrain.client.homeroom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HomeArrivalFovTest {

    @AfterEach
    void reset() {
        HomeArrivalFov.reset();
    }

    @Test
    void noEaseMeansTheGamesOwnFov() {
        assertEquals(1.0, HomeArrivalFov.progress(123L), 1e-9);
        assertEquals(70.0, HomeArrivalFov.fovAt(1.0, 70.0), 1e-9);
    }

    @Test
    void startsNarrowAndEasesOutSmoothly() {
        HomeArrivalFov.begin(1000L);
        assertEquals(0.0, HomeArrivalFov.progress(1000L), 1e-9);
        assertEquals(HomeArrivalFov.START_FOV, HomeArrivalFov.fovAt(HomeArrivalFov.progress(1000L), 70.0), 1e-9);
        double mid = HomeArrivalFov.progress(1000L + HomeArrivalFov.EASE_MS / 2);
        assertEquals(0.5, mid, 1e-9, "smoothstep is symmetric");
        assertEquals(1.0, HomeArrivalFov.progress(1000L + HomeArrivalFov.EASE_MS), 1e-9);
        assertEquals(70.0, HomeArrivalFov.fovAt(1.0, 70.0), 1e-9);
        double quarter = HomeArrivalFov.progress(1000L + HomeArrivalFov.EASE_MS / 4);
        assertTrue(quarter < 0.25, "eases in: slower than linear at the start");
    }
}
