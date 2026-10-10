package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveHeadViewerTest {

    @Test
    void countdownRunsTwoSegmentsPlusSlackThenHoldsAtOne() {
        long start = 1_000_000L;
        assertEquals(22, LiveHeadViewer.countdownSeconds(start, 10, start), "2 × 10 s segments + 2 s upload slack");
        assertEquals(22, LiveHeadViewer.countdownSeconds(start, 10, start + 1), "rounds up, never shows 21.999 as 21");
        assertEquals(12, LiveHeadViewer.countdownSeconds(start, 10, start + 10_000));
        assertEquals(1, LiveHeadViewer.countdownSeconds(start, 10, start + 21_500));
        assertEquals(1, LiveHeadViewer.countdownSeconds(start, 10, start + 60_000), "a late feed holds at 1, never 0 or negative");
    }

    @Test
    void boxIsSixteenByNineInTheTopRightCorner() {
        LiveHeadViewer.Box b = LiveHeadViewer.layout(480, 160, false, 9);
        assertEquals(90, b.h());
        assertEquals(480 - LiveHeadViewer.EDGE, b.x() + b.w(), "flush to the right margin");
        assertEquals(LiveHeadViewer.EDGE, b.y());
    }

    @Test
    void whileStreamingTheBoxSitsUnderTheLiveBadge() {
        LiveHeadViewer.Box b = LiveHeadViewer.layout(480, 160, true, 9);
        assertEquals(LiveRecOverlay.innerRight(480), b.x() + b.w(), "right-aligned with the badge");
        assertTrue(b.y() >= LiveRecOverlay.badgeBottom(9) + 1, "starts below the badge, not over it");
    }

    @Test
    void countdownClearsOnlyOnOwnLivePicture() {
        String mine = "https://cdn/live/abc/live.m3u8";
        var own = new LiveFeedSource.Frame(LiveFeedSource.Kind.PICTURE, mine, false, -1, false);
        var other = new LiveFeedSource.Frame(LiveFeedSource.Kind.PICTURE, "https://cdn/live/old/live.m3u8", false, -1, false);
        var replay = new LiveFeedSource.Frame(LiveFeedSource.Kind.PICTURE, mine, true, -1, false);
        var waiting = new LiveFeedSource.Frame(LiveFeedSource.Kind.WAITING, mine, false, -1, false);
        assertTrue(LiveHeadViewer.showingOwnBroadcast(own, mine));
        assertFalse(LiveHeadViewer.showingOwnBroadcast(other, mine), "the previous streamer is still on");
        assertFalse(LiveHeadViewer.showingOwnBroadcast(replay, mine));
        assertFalse(LiveHeadViewer.showingOwnBroadcast(waiting, mine), "no picture yet");
        assertFalse(LiveHeadViewer.showingOwnBroadcast(own, null));
    }
}
