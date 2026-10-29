package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class HeadViewerCaptionTest {

    private static Status live(String streamer, Status second) {
        return new Status(true, "s", "https://x/p.m3u8", streamer, 1, 10, true, null, false, List.of(), null, second);
    }

    @Test
    @DisplayName("each pane is captioned with its own stream's streamer")
    void namesEachStream() {
        Status status = live("Alice", live("Bob", null));
        assertEquals("Alice", HeadViewerCaption.nameFor(status, false));
        assertEquals("Bob", HeadViewerCaption.nameFor(status, true));
    }

    @Test
    @DisplayName("no caption while the channel is dark (replay), with no second stream, or with no name")
    void nothingWithoutALiveName() {
        assertNull(HeadViewerCaption.nameFor(Status.OFFLINE, false));
        assertNull(HeadViewerCaption.nameFor(null, false));
        assertNull(HeadViewerCaption.nameFor(live("Alice", null), true));
        assertNull(HeadViewerCaption.nameFor(live("  ", null), false));
        assertNull(HeadViewerCaption.nameFor(live(null, null), false));
    }

    @Test
    @DisplayName("withCaption grows the box down over the caption strip (config unloaded = setting on)")
    void withCaptionGrowsDown() {
        LiveHeadViewer.Box b = new LiveHeadViewer.Box(10, 8, 160, 90);
        assertEquals(new LiveHeadViewer.Box(10, 8, 160, 90 + HeadViewerCaption.HEIGHT), HeadViewerCaption.withCaption(b));
    }
}
