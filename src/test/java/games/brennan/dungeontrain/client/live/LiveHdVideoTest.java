package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HD Streams only ever raises a streamer on the default size or above, on an upload that carries it. */
final class LiveHdVideoTest {
    private static final LiveFeedClient.Video HD = new LiveFeedClient.Video(1920, 4500);
    private static final int AUDIO = 192;

    @Test
    void theDefault720pStreamerGoes1080p() {
        assertTrue(LiveHdVideo.applies(1280, HD, 0, AUDIO), "upload not measured yet");
        assertTrue(LiveHdVideo.applies(1280, HD, 20_000, AUDIO), "fast upload");
        assertEquals(1920, LiveHdVideo.width(1280, HD, true));
        assertEquals(4500, LiveHdVideo.kbps(2500, HD, true));
    }

    @Test
    void noVideoChangesNothing() {
        assertFalse(LiveHdVideo.applies(1280, null, 0, AUDIO));
        assertEquals(1280, LiveHdVideo.width(1280, null, false));
        assertEquals(2500, LiveHdVideo.kbps(2500, null, false));
    }

    @Test
    void aSmallerChosenSizeIsKept() {
        assertFalse(LiveHdVideo.applies(960, HD, 0, AUDIO));
    }

    @Test
    void aMidSpeedUploadKeeps720pRatherThanAStarved1080p() {
        assertFalse(LiveHdVideo.applies(1280, HD, 3000, AUDIO), "3 Mbit/s carries 720p at 2.5, not 1080p at 4.5");
        assertFalse(LiveHdVideo.applies(1280, HD, 6000, AUDIO), "4500 + 192 > 0.75 x 6000");
        assertTrue(LiveHdVideo.applies(1280, HD, 6300, AUDIO), "4692 <= 0.75 x 6300");
        assertEquals(1280, LiveHdVideo.width(1280, HD, false));
        assertEquals(2500, LiveHdVideo.kbps(2500, HD, false));
    }

    @Test
    void neverLowersAHigherOwnSetting() {
        assertEquals(1920, LiveHdVideo.width(1920, new LiveFeedClient.Video(1600, 3000), true));
        assertEquals(6000, LiveHdVideo.kbps(6000, HD, true));
        assertEquals(4500, LiveHdVideo.kbps(2500, HD, true), "already 1080p, but at a 720p bitrate");
    }
}
