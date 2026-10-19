package games.brennan.dungeontrain.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.brennan.dungeontrain.net.SpawnDeckHoldPacket;
import org.junit.jupiter.api.Test;

/**
 * The rules behind {@code /dtp}'s wait and landing watch (issue #2133): under load the new
 * train's first physics tick can be many seconds away, and a teleport onto its deck is not proof
 * the player stays there.
 */
class DtpPlacementServiceTest {

    private static final double DECK = 81.0;

    @Test
    void waitForAnExistingTrainIsLongerThanForNone() {
        int justPastShort = DtpPlacementService.NO_TRAIN_TICKS;
        assertTrue(DtpPlacementService.waitTimedOut(justPastShort, false));
        assertFalse(DtpPlacementService.waitTimedOut(justPastShort, true));
        assertTrue(DtpPlacementService.waitTimedOut(DtpPlacementService.SPAWN_CAPTURE_TICKS, true));
        assertFalse(DtpPlacementService.waitTimedOut(DtpPlacementService.SPAWN_CAPTURE_TICKS - 1, true));
    }

    @Test
    void landingWatchOutlastsTheClientDeckHold() {
        assertTrue(DtpPlacementService.LANDING_WATCH_TICKS > SpawnDeckHoldPacket.DEFAULT_HOLD_TICKS);
    }

    @Test
    void standingOnOrAboveTheDeckIsNotAFall() {
        assertFalse(DtpPlacementService.hasFallen(DECK, DECK));
        assertFalse(DtpPlacementService.hasFallen(DECK + 6.0, DECK)); // on a carriage roof
        assertFalse(DtpPlacementService.hasFallen(DECK - DtpPlacementService.FALL_MARGIN_BLOCKS, DECK));
    }

    @Test
    void droppingBelowTheMarginIsAFall() {
        assertTrue(DtpPlacementService.hasFallen(DECK - DtpPlacementService.FALL_MARGIN_BLOCKS - 0.01, DECK));
        assertTrue(DtpPlacementService.hasFallen(-61.0, DECK)); // the void drop from the benchmark
    }

    @Test
    void onDeckKeepsWatchingThenLands() {
        assertEquals(DtpPlacementService.LandingStep.KEEP_WATCHING,
            DtpPlacementService.decideLanding(DECK, DECK, 1, 0));
        assertEquals(DtpPlacementService.LandingStep.LANDED,
            DtpPlacementService.decideLanding(DECK, DECK, DtpPlacementService.LANDING_WATCH_TICKS, 0));
    }

    @Test
    void aFallRelandsUntilTheCapThenGivesUp() {
        double fell = DECK - 20.0;
        for (int relands = 0; relands < DtpPlacementService.MAX_RELANDS; relands++) {
            assertEquals(DtpPlacementService.LandingStep.RELAND,
                DtpPlacementService.decideLanding(fell, DECK, 5, relands));
        }
        assertEquals(DtpPlacementService.LandingStep.GIVE_UP,
            DtpPlacementService.decideLanding(fell, DECK, 5, DtpPlacementService.MAX_RELANDS));
    }

    @Test
    void aFallOnTheLastWatchedTickIsStillCaught() {
        assertEquals(DtpPlacementService.LandingStep.RELAND,
            DtpPlacementService.decideLanding(DECK - 20.0, DECK, DtpPlacementService.LANDING_WATCH_TICKS, 0));
    }
}
