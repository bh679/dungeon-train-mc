package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.event.LiveCloseCallEvents.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
