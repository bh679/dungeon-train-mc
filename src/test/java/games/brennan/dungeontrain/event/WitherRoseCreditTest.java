package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WitherRoseCreditTest {

    private static final long WINDOW = WitherRoseCredit.EXPOSURE_WINDOW_TICKS;

    @Test
    void contactThisTickCounts() {
        assertTrue(WitherRoseCredit.withinWindow(1000L, 1000L));
    }

    @Test
    void contactAtWindowEdgeCounts() {
        assertTrue(WitherRoseCredit.withinWindow(1000L, 1000L + WINDOW));
    }

    @Test
    void contactPastWindowDoesNotCount() {
        assertFalse(WitherRoseCredit.withinWindow(1000L, 1000L + WINDOW + 1));
    }

    @Test
    void contactAheadOfNowDoesNotCount() {
        assertFalse(WitherRoseCredit.withinWindow(2000L, 1000L));
    }

    @Test
    void windowCoversLingeringWitherPlusOneDamageTick() {
        // The rose's Wither lasts 40 ticks after the victim steps off; level I hurts every 40.
        assertTrue(WINDOW >= 80L);
    }
}
