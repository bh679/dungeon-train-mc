package games.brennan.dungeontrain.net.relay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Everyone watches the public feed; Free Play switches to the dev channel only while a dev streams. */
final class LiveFeedClientChannelTest {

    @Test
    @DisplayName("Free Play shows the dev channel only while a dev streams; otherwise everyone sees the public feed")
    void freePlayWatchesDevOnlyWhileLive() {
        assertEquals(LiveFeedClient.Channel.DEV, LiveFeedClient.viewerChannel(true, true));
        assertEquals(LiveFeedClient.Channel.LIVE, LiveFeedClient.viewerChannel(true, false));
        assertEquals(LiveFeedClient.Channel.LIVE, LiveFeedClient.viewerChannel(false, true));
        assertEquals(LiveFeedClient.Channel.LIVE, LiveFeedClient.viewerChannel(false, false));
    }
}
