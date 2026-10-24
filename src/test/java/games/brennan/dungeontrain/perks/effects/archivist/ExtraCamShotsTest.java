package games.brennan.dungeontrain.perks.effects.archivist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExtraCamShotsTest {

    @Test
    void rankZeroNeverReloads() {
        assertEquals(0, ExtraCamShots.extraShots(0, 0f));
        assertEquals(0, ExtraCamShots.extraShots(-1, 0f));
    }

    @Test
    void rankOneIsACoinFlip() {
        assertEquals(1, ExtraCamShots.extraShots(1, 0f));
        assertEquals(1, ExtraCamShots.extraShots(1, 0.49f));
        assertEquals(0, ExtraCamShots.extraShots(1, 0.5f));
        assertEquals(0, ExtraCamShots.extraShots(1, 0.99f));
    }

    @Test
    void evenRanksAreGuaranteed() {
        assertEquals(1, ExtraCamShots.extraShots(2, 0f));
        assertEquals(1, ExtraCamShots.extraShots(2, 0.99f));
        assertEquals(2, ExtraCamShots.extraShots(4, 0.99f));
    }

    @Test
    void oddRanksAddAFlipOnTop() {
        assertEquals(2, ExtraCamShots.extraShots(3, 0.1f));
        assertEquals(1, ExtraCamShots.extraShots(3, 0.9f));
        assertEquals(3, ExtraCamShots.extraShots(5, 0.1f));
        assertEquals(2, ExtraCamShots.extraShots(5, 0.9f));
    }
}
