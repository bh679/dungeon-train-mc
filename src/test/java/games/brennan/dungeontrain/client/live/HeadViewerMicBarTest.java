package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient.Mic;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HeadViewerMicBarTest {

    private static Status live(String url, Mic mic) {
        return new Status(true, "s", url, "Alice", 1, 4, true, null, false, List.of(), null, null, null, mic);
    }

    @Test
    void theBarIsBottomAnchoredAndFullAtFullLevel() {
        assertEquals(0, HeadViewerMicBar.barHeight(0f, 90));
        assertEquals(90, HeadViewerMicBar.barHeight(1f, 90));
        assertEquals(45, HeadViewerMicBar.barHeight(0.5f, 90));
        assertEquals(90, HeadViewerMicBar.barHeight(3f, 90), "clamped");
        assertEquals(0, HeadViewerMicBar.barHeight(-1f, 90), "clamped");
        assertEquals(1, HeadViewerMicBar.WIDTH, "one GUI pixel wide");
    }

    @Test
    void noStatusOrNoMicMeansNoBar() {
        String url = "https://x/live.m3u8";
        assertFalse(HeadViewerMicBar.shows(null, false, null));
        assertFalse(HeadViewerMicBar.shows(Status.OFFLINE, false, null));
        assertFalse(HeadViewerMicBar.shows(live(url, null), false, null), "an older relay says nothing about the mic");
        assertFalse(HeadViewerMicBar.shows(live(url, new Mic(true, false)), false, null), "mic off");
        assertFalse(HeadViewerMicBar.shows(live(url, new Mic(true, true)), false, null), "nothing decoding yet");
        assertFalse(HeadViewerMicBar.shows(live(url, new Mic(true, true)), true, null), "no second stream");
    }
}
