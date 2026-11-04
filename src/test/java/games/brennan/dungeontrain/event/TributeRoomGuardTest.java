package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TributeRoomGuardTest {

    @Test
    void liveRoomIsLookOnlyForEverything() {
        assertTrue(TributeRoomGuard.denies(true, false, false));
        assertTrue(TributeRoomGuard.denies(true, false, true));
    }

    @Test
    void visitOnlyKeepsTheTv() {
        assertFalse(TributeRoomGuard.denies(false, true, false));
        assertTrue(TributeRoomGuard.denies(false, true, true));
    }

    @Test
    void anywhereElseIsFree() {
        assertFalse(TributeRoomGuard.denies(false, false, true));
        assertFalse(TributeRoomGuard.denies(false, false, false));
    }
}
