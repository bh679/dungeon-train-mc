package games.brennan.dungeontrain.perks.effects.archivist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExtraCamShotsTest {

    @Test
    void rankZeroNeverReloads() {
        assertEquals(0, ExtraCamShots.extraShots(0, 0));
        assertEquals(0, ExtraCamShots.extraShots(-1, 0));
    }

    @Test
    void rankOneIsEverySecondCameraStartingWithTheFirst() {
        assertEquals(1, ExtraCamShots.extraShots(1, 0));
        assertEquals(0, ExtraCamShots.extraShots(1, 1));
        assertEquals(1, ExtraCamShots.extraShots(1, 2));
        assertEquals(0, ExtraCamShots.extraShots(1, 3));
    }

    @Test
    void evenRanksGiveEveryCameraTheSame() {
        for (int camera = 0; camera < 4; camera++) {
            assertEquals(1, ExtraCamShots.extraShots(2, camera));
            assertEquals(2, ExtraCamShots.extraShots(4, camera));
        }
    }

    @Test
    void oddRanksAddOneMoreOnEverySecondCamera() {
        assertEquals(2, ExtraCamShots.extraShots(3, 0));
        assertEquals(1, ExtraCamShots.extraShots(3, 1));
        assertEquals(3, ExtraCamShots.extraShots(5, 2));
        assertEquals(2, ExtraCamShots.extraShots(5, 3));
    }

    @Test
    void averagesHalfAShotPerRank() {
        for (int rank = 0; rank <= 9; rank++) {
            int total = 0;
            for (int camera = 0; camera < 100; camera++) total += ExtraCamShots.extraShots(rank, camera);
            assertEquals(ExtraCamShots.CHANCE_PER_RANK * rank, total / 100.0, 1e-9);
        }
    }
}
