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
        LiveHeadViewer.Box b = LiveHeadViewer.layout(480, 160);
        assertEquals(90, b.h());
        assertEquals(480 - LiveHeadViewer.EDGE, b.x() + b.w(), "flush to the right margin");
        assertEquals(LiveHeadViewer.EDGE, b.y());
    }

    @Test
    void theBoxStaysClearOfTheTopLeftBadge() {
        LiveHeadViewer.Box b = LiveHeadViewer.layout(480, 160);
        assertTrue(b.x() > LiveRecOverlay.innerLeft() + 120, "the badge is top-left; the box is top-right");
    }

    @Test
    void everySizeStaysInTheTopRightCorner() {
        for (int w : new int[] {80, 120, 160, 240}) {
            LiveHeadViewer.Box b = LiveHeadViewer.layout(480, w);
            assertEquals(480 - LiveHeadViewer.EDGE, b.x() + b.w(), "flush right at " + w);
            assertEquals(LiveHeadViewer.EDGE, b.y());
        }
    }

    @Test
    void aClickCountsInsideTheBoxAndItsBorderOnly() {
        LiveHeadViewer.Box b = LiveHeadViewer.layout(480, 160); // x 312..472, y 8..98
        assertTrue(LiveHeadViewer.contains(b, 312, 8));
        assertTrue(LiveHeadViewer.contains(b, 400, 50));
        assertTrue(LiveHeadViewer.contains(b, 311, 7), "the 1-pixel border counts");
        assertFalse(LiveHeadViewer.contains(b, 310, 50));
        assertFalse(LiveHeadViewer.contains(b, 400, 99 + LiveHeadViewer.BORDER));
    }

    @Test
    void resizesFromInventoryCreativeAndChatOnly() {
        assertTrue(LiveHeadViewer.resizesFrom(net.minecraft.client.gui.screens.inventory.InventoryScreen.class));
        assertTrue(LiveHeadViewer.resizesFrom(net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen.class));
        assertTrue(LiveHeadViewer.resizesFrom(net.minecraft.client.gui.screens.ChatScreen.class));
        assertFalse(LiveHeadViewer.resizesFrom(net.minecraft.client.gui.screens.PauseScreen.class));
        assertFalse(LiveHeadViewer.isResizeScreen(null));
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
