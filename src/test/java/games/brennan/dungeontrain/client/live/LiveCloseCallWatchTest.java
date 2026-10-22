package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveCloseCallWatchTest {

    @Test
    @DisplayName("polls every 3 s while watching, and stands aside while the chat bridge is connected")
    void shouldPoll() {
        assertTrue(LiveCloseCallWatch.shouldPoll(true, false, 0, 3000));
        assertFalse(LiveCloseCallWatch.shouldPoll(true, false, 1000, 3000));
        assertFalse(LiveCloseCallWatch.shouldPoll(false, false, 0, 9000));
        assertFalse(LiveCloseCallWatch.shouldPoll(true, true, 0, 9000));
    }
}
