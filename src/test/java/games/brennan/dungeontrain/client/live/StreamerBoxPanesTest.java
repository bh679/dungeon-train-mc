package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.config.LiveFeedClientConfig.StreamerBoxStreams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static games.brennan.dungeontrain.config.LiveFeedClientConfig.StreamerBoxStreams.BOTH;
import static games.brennan.dungeontrain.config.LiveFeedClientConfig.StreamerBoxStreams.NONE;
import static games.brennan.dungeontrain.config.LiveFeedClientConfig.StreamerBoxStreams.OTHER;
import static games.brennan.dungeontrain.config.LiveFeedClientConfig.StreamerBoxStreams.OWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StreamerBoxPanesTest {

    /** A tall GUI: two panes always fit. */
    private static final int H = 1000;
    private static final float R = 16f / 9f;
    private static final long STREAM = 1_000L;

    @AfterEach
    void reset() {
        StreamerBoxPanes.reset();
    }

    private static List<HeadViewerPanes.Pane> now(boolean otherLive, StreamerBoxStreams option) {
        return StreamerBoxPanes.current(480, H, 160, otherLive, false, STREAM, option, R, R);
    }

    @Test
    @DisplayName("default: the streamer's own stream alone, in the usual top-right box")
    void ownByDefault() {
        List<HeadViewerPanes.Pane> panes = now(true, OWN);
        assertEquals(1, panes.size());
        assertFalse(panes.get(0).second());
        assertEquals(LiveHeadViewer.layout(480, 160), panes.get(0).box());
    }

    @Test
    @DisplayName("each setting: other alone in the top spot, both stacked own-first, none draws nothing")
    void eachSetting() {
        List<HeadViewerPanes.Pane> other = now(true, OTHER);
        assertEquals(1, other.size());
        assertTrue(other.get(0).second());
        assertEquals(LiveHeadViewer.layout(480, 160), other.get(0).box());

        List<HeadViewerPanes.Pane> both = now(true, BOTH);
        assertEquals(2, both.size());
        assertFalse(both.get(0).second());
        assertTrue(both.get(1).second());

        assertTrue(now(true, NONE).isEmpty());
    }

    @Test
    @DisplayName("nobody else live: own stream alone whatever the setting, except none")
    void nobodyElseLive() {
        for (StreamerBoxStreams option : new StreamerBoxStreams[] {OWN, OTHER, BOTH}) {
            List<HeadViewerPanes.Pane> panes = now(false, option);
            assertEquals(1, panes.size(), option.name());
            assertFalse(panes.get(0).second(), option.name());
        }
        assertTrue(now(false, NONE).isEmpty());
    }

    @Test
    @DisplayName("clicks: own pane brings both, then a pane solos it, like a viewer's box")
    void clicks() {
        LiveHeadViewer.Box top = LiveHeadViewer.layout(480, 160);
        double x = top.x() + 5, y = top.y() + 5;
        assertTrue(StreamerBoxPanes.apply(now(true, OWN), x, y, STREAM, OWN));
        List<HeadViewerPanes.Pane> both = now(true, OWN);
        assertEquals(2, both.size());

        LiveHeadViewer.Box lower = both.get(1).box();
        assertTrue(StreamerBoxPanes.apply(both, lower.x() + 5, lower.y() + 5, STREAM, OWN));
        List<HeadViewerPanes.Pane> other = now(true, OWN);
        assertEquals(1, other.size());
        assertTrue(other.get(0).second());

        assertFalse(StreamerBoxPanes.apply(other, 1, H - 1, STREAM, OWN), "a click off the box changes nothing");
    }

    @Test
    @DisplayName("a click lasts for that stream only, and is forgotten when the other stream goes dark")
    void clickIsPerStream() {
        LiveHeadViewer.Box top = LiveHeadViewer.layout(480, 160);
        StreamerBoxPanes.apply(now(true, OWN), top.x() + 5, top.y() + 5, STREAM, OWN);
        assertEquals(BOTH, StreamerBoxPanes.choice(STREAM, OWN));
        assertEquals(OWN, StreamerBoxPanes.choice(STREAM + 1, OWN), "a new stream starts from the setting");

        now(false, OWN);
        assertEquals(OWN, StreamerBoxPanes.choice(STREAM, OWN));
    }

    @Test
    @DisplayName("none ignores clicks")
    void noneIgnoresClicks() {
        assertEquals(NONE, StreamerBoxPanes.click(List.of(), NONE, 0, 0));
    }
}
