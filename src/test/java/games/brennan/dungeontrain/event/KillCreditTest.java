package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KillCreditTest {

    private static final long WINDOW = KillCredit.KNOCKBACK_CREDIT_WINDOW_TICKS;

    @Test
    void strikeThisTickCounts() {
        assertTrue(KillCredit.withinWindow(1000L, 1000L));
    }

    @Test
    void strikeAtWindowEdgeCounts() {
        assertTrue(KillCredit.withinWindow(1000L, 1000L + WINDOW));
    }

    @Test
    void strikePastWindowDoesNotCount() {
        assertFalse(KillCredit.withinWindow(1000L, 1000L + WINDOW + 1));
    }

    @Test
    void strikeAheadOfNowDoesNotCount() {
        assertFalse(KillCredit.withinWindow(2000L, 1000L));
    }

    @Test
    void windowOutlastsVanillaKillCredit() {
        // Vanilla's kill credit lasts 100 ticks; a fall to the void can take longer than that.
        assertTrue(WINDOW > 100L);
    }
}
