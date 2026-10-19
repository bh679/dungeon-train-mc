package games.brennan.dungeontrain.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import games.brennan.dungeontrain.net.SpawnDeckHoldPacket;
import org.junit.jupiter.api.Test;

/**
 * The rules behind {@code /dtp}'s wait and landing watch (issue #2133): under load the new
 * train's first physics tick can be many seconds away, and only a fall through the void (not a
 * portal room, not the track bed) is a landing to redo.
 */
class DtpPlacementServiceTest {

    private static final double DECK = 79.0;
    private static final int MIN_BUILD = -64;

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
    void standingOnTheTrainOrAnywhereAboveTheFloorIsNotAFall() {
        assertFalse(DtpPlacementService.hasFallenIntoVoid(DECK, MIN_BUILD));
        assertFalse(DtpPlacementService.hasFallenIntoVoid(DECK + 6.0, MIN_BUILD)); // a carriage roof
        assertFalse(DtpPlacementService.hasFallenIntoVoid(MIN_BUILD, MIN_BUILD));
    }

    @Test
    void aPortalRoomIsNotAFall() {
        // The benchmark's "void drop": the player had been swapped into a portal room standing on
        // its floor at y=-62. Pulling them back out would break the portal.
        assertFalse(DtpPlacementService.hasFallenIntoVoid(-61.0, MIN_BUILD));
        assertEquals(DtpPlacementService.LandingStep.KEEP_WATCHING,
            DtpPlacementService.decideLanding(-61.0, MIN_BUILD, 5, 0));
    }

    @Test
    void belowTheWorldFloorIsAFall() {
        assertTrue(DtpPlacementService.hasFallenIntoVoid(MIN_BUILD - 0.01, MIN_BUILD));
        assertTrue(DtpPlacementService.hasFallenIntoVoid(-361.0, MIN_BUILD)); // seen in the benchmark
    }

    @Test
    void onTheTrainKeepsWatchingThenLands() {
        assertEquals(DtpPlacementService.LandingStep.KEEP_WATCHING,
            DtpPlacementService.decideLanding(DECK, MIN_BUILD, 1, 0));
        assertEquals(DtpPlacementService.LandingStep.LANDED,
            DtpPlacementService.decideLanding(DECK, MIN_BUILD, DtpPlacementService.LANDING_WATCH_TICKS, 0));
    }

    @Test
    void aVoidFallRelandsUntilTheCapThenGivesUp() {
        double falling = MIN_BUILD - 20.0;
        for (int relands = 0; relands < DtpPlacementService.MAX_RELANDS; relands++) {
            assertEquals(DtpPlacementService.LandingStep.RELAND,
                DtpPlacementService.decideLanding(falling, MIN_BUILD, 5, relands));
        }
        assertEquals(DtpPlacementService.LandingStep.GIVE_UP,
            DtpPlacementService.decideLanding(falling, MIN_BUILD, 5, DtpPlacementService.MAX_RELANDS));
    }

    @Test
    void aFallOnTheLastWatchedTickIsStillCaught() {
        assertEquals(DtpPlacementService.LandingStep.RELAND,
            DtpPlacementService.decideLanding(MIN_BUILD - 20.0, MIN_BUILD, DtpPlacementService.LANDING_WATCH_TICKS, 0));
    }
}
