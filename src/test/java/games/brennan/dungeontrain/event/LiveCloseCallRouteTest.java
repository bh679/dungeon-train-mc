package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.event.LiveCloseCallEvents.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveCloseCallRouteTest {

    @Test
    @DisplayName("a close call goes to the streamer on air, to the train when nobody is live, else nowhere")
    void route() {
        assertEquals(Route.STREAMER, LiveCloseCallEvents.route(false, true, false));
        assertEquals(Route.TRAIN, LiveCloseCallEvents.route(false, false, true));
        assertNull(LiveCloseCallEvents.route(false, false, false), "can't tell whether anyone is live");
        assertNull(LiveCloseCallEvents.route(true, true, false), "blocked: AFK, streaming or can't take one");
        assertNull(LiveCloseCallEvents.route(true, false, true));
    }

    @Test
    @DisplayName("a free second channel sends The Challenge to the train, the rest to the streamer on air")
    void secondChannelFree() {
        assertEquals(Route.TRAIN_FOR_CHALLENGE, LiveCloseCallEvents.route(false, true, false, true));
        assertEquals(Route.TRAIN, LiveCloseCallEvents.route(false, false, false, true), "free channel, nobody else on air");
        assertEquals(Route.STREAMER, LiveCloseCallEvents.route(false, true, false, false), "both channels taken");
        assertNull(LiveCloseCallEvents.route(true, true, false, true), "blocked");
    }

    @Test
    @DisplayName("the train takes only The Challenge; the streamer takes the rest, and The Challenge too unless the train has it")
    void takes() {
        assertTrue(LiveCloseCallEvents.takes(Route.TRAIN, true, true));
        assertFalse(LiveCloseCallEvents.takes(Route.TRAIN, true, false));
        assertTrue(LiveCloseCallEvents.takes(Route.STREAMER, false, true));
        assertTrue(LiveCloseCallEvents.takes(Route.STREAMER, true, false));
        assertFalse(LiveCloseCallEvents.takes(Route.STREAMER, true, true));
    }
}
