package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HomeRoomGuardTest {

    @Test
    void liveRoomIsLookOnlyForEverything() {
        assertTrue(HomeRoomGuard.denies(true, false, false));
        assertTrue(HomeRoomGuard.denies(true, false, true));
    }

    @Test
    void visitOnlyKeepsTheTv() {
        assertFalse(HomeRoomGuard.denies(false, true, false));
        assertTrue(HomeRoomGuard.denies(false, true, true));
    }

    @Test
    void anywhereElseIsFree() {
        assertFalse(HomeRoomGuard.denies(false, false, true));
        assertFalse(HomeRoomGuard.denies(false, false, false));
    }
}
