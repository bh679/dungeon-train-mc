package games.brennan.dungeontrain.net.relay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Free Play TVs watch the dev channel; everyone else the public feed. */
final class LiveFeedClientChannelTest {

    @Test
    @DisplayName("a Free Play run's TVs show the dev channel, any other run's the public feed")
    void freePlayWatchesDev() {
        assertEquals(LiveFeedClient.Channel.DEV, LiveFeedClient.viewerChannel(true));
        assertEquals(LiveFeedClient.Channel.LIVE, LiveFeedClient.viewerChannel(false));
    }
}
