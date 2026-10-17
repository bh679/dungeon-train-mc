package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.event.LiveGiftServer.Refusal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LiveGiftServerTest {

    @Test
    @DisplayName("a gifted Live Eye is refused in Kid mode, with Livestreaming off, when banned, when one is held, in Free Play, or with the internet off")
    void refusal() {
        assertNull(LiveGiftServer.refusal(false, true, false, false, false, false));
        assertEquals(Refusal.NOT_ALLOWED, LiveGiftServer.refusal(true, true, false, false, false, false));
        assertEquals(Refusal.NOT_ALLOWED, LiveGiftServer.refusal(false, false, false, false, false, false));
        assertEquals(Refusal.BANNED, LiveGiftServer.refusal(false, true, true, false, false, false));
        assertEquals(Refusal.HOLDS_ONE, LiveGiftServer.refusal(false, true, false, true, false, false));
        assertEquals(Refusal.FREE_PLAY, LiveGiftServer.refusal(false, true, false, false, true, false));
        assertEquals(Refusal.NO_INTERNET, LiveGiftServer.refusal(false, true, false, false, false, true));
    }
}
