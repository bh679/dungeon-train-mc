package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HD Streams only ever raises a streamer on the default size or above; it never lowers anything. */
final class LiveHdVideoTest {
    private static final LiveFeedClient.Video HD = new LiveFeedClient.Video(1920, 4500);

    @Test
    void theDefault720pStreamerGoes1080p() {
        assertTrue(LiveHdVideo.applies(1280, HD));
        assertEquals(1920, LiveHdVideo.width(1280, HD));
        assertEquals(4500, LiveHdVideo.kbps(2500, 1280, HD));
    }

    @Test
    void noVideoChangesNothing() {
        assertFalse(LiveHdVideo.applies(1280, null));
        assertEquals(1280, LiveHdVideo.width(1280, null));
        assertEquals(2500, LiveHdVideo.kbps(2500, 1280, null));
    }

    @Test
    void aSmallerChosenSizeIsKept() {
        assertFalse(LiveHdVideo.applies(960, HD));
        assertEquals(960, LiveHdVideo.width(960, HD));
        assertEquals(2500, LiveHdVideo.kbps(2500, 960, HD));
    }

    @Test
    void neverLowersAHigherOwnSetting() {
        assertEquals(1920, LiveHdVideo.width(1920, new LiveFeedClient.Video(1600, 3000)));
        assertEquals(6000, LiveHdVideo.kbps(6000, 1920, HD));
        assertEquals(4500, LiveHdVideo.kbps(2500, 1920, HD), "already 1080p, but at a 720p bitrate");
    }
}
