package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TributeRoomGuardTest {

    @Test
    void liveRoomIsLookOnlyForEverything() {
        assertTrue(TributeRoomGuard.denies(true, false, false, false, false));
        assertTrue(TributeRoomGuard.denies(true, false, true, false, false));
    }

    @Test
    void visitOnlyKeepsTheTv() {
        assertFalse(TributeRoomGuard.denies(false, true, false, true, false));
        assertTrue(TributeRoomGuard.denies(false, true, false, false, false), "someone else's visit is look-only");
        assertTrue(TributeRoomGuard.denies(false, true, true, true, false));
    }

    @Test
    void anywhereElseIsFree() {
        assertFalse(TributeRoomGuard.denies(false, false, true, false, false));
        assertFalse(TributeRoomGuard.denies(false, false, false, false, false));
    }

    @Test
    void ownerBuildsInTheirLiveRoom() {
        assertFalse(TributeRoomGuard.denies(true, false, false, false, true));
        assertTrue(TributeRoomGuard.denies(true, false, false, false, false));
    }

    @Test
    void ownerPlacesIntoAirAndTakesOutOnlyWhatTheyPlaced() {
        assertTrue(TributeRoomGuard.ownLiveBuild(true, true, true, false), "place into air");
        assertFalse(TributeRoomGuard.ownLiveBuild(true, true, false, false), "place over an original block");
        assertTrue(TributeRoomGuard.ownLiveBuild(true, false, false, true), "break own block");
        assertFalse(TributeRoomGuard.ownLiveBuild(true, false, false, false), "break an original block");
        assertFalse(TributeRoomGuard.ownLiveBuild(false, true, true, true), "not the owner");
        assertFalse(TributeRoomGuard.ownLiveBuild(false, false, false, true), "not the owner");
    }
}
