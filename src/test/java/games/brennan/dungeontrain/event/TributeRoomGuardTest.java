package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TributeRoomGuardTest {

    @Test
    void liveRoomIsLookOnlyForEverything() {
        assertTrue(TributeRoomGuard.denies(true, false, false, false));
        assertTrue(TributeRoomGuard.denies(true, false, true, false));
    }

    @Test
    void visitOnlyKeepsTheTv() {
        assertFalse(TributeRoomGuard.denies(false, true, false, true));
        assertTrue(TributeRoomGuard.denies(false, true, false, false), "someone else's visit is look-only");
        assertTrue(TributeRoomGuard.denies(false, true, true, true));
    }

    @Test
    void anywhereElseIsFree() {
        assertFalse(TributeRoomGuard.denies(false, false, true, false));
        assertFalse(TributeRoomGuard.denies(false, false, false, false));
    }
}
