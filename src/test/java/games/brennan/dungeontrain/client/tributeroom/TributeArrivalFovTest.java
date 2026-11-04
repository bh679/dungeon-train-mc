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
        TributeArrivalFov.begin(1000L);
        assertEquals(0.0, TributeArrivalFov.progress(1000L), 1e-9);
        assertEquals(TributeArrivalFov.START_FOV, TributeArrivalFov.fovAt(TributeArrivalFov.progress(1000L), 70.0), 1e-9);
        double mid = TributeArrivalFov.progress(1000L + TributeArrivalFov.EASE_MS / 2);
        assertEquals(0.5, mid, 1e-9, "smoothstep is symmetric");
        assertEquals(1.0, TributeArrivalFov.progress(1000L + TributeArrivalFov.EASE_MS), 1e-9);
        assertEquals(70.0, TributeArrivalFov.fovAt(1.0, 70.0), 1e-9);
        double quarter = TributeArrivalFov.progress(1000L + TributeArrivalFov.EASE_MS / 4);
        assertTrue(quarter < 0.25, "eases in: slower than linear at the start");
    }
}
