package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static games.brennan.dungeontrain.client.live.HeadViewerPanes.Choice.BOTH;
import static games.brennan.dungeontrain.client.live.HeadViewerPanes.Choice.MAIN_ONLY;
import static games.brennan.dungeontrain.client.live.HeadViewerPanes.Choice.SECOND_ONLY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HeadViewerPanesTest {

    /** A tall GUI: two panes always fit unless a test says otherwise. */
    private static final int H = 1000;

    @AfterEach
    void reset() {
        HeadViewerPanes.reset();
    }

    @Test
    @DisplayName("one stream live: the one box in the top-right corner, whatever was chosen before")
    void oneStreamIsTheOldBox() {
        List<HeadViewerPanes.Pane> panes = HeadViewerPanes.panes(480, H, 160, false, false, SECOND_ONLY);
        assertEquals(1, panes.size());
        assertFalse(panes.get(0).second());
        assertEquals(LiveHeadViewer.layout(480, 160), panes.get(0).box());
    }

    @Test
    @DisplayName("two streams: the second stream sits under the first, same size and column")
    void twoStreamsStacked() {
        List<HeadViewerPanes.Pane> panes = HeadViewerPanes.panes(480, H, 160, true, false, BOTH);
        assertEquals(2, panes.size());
        LiveHeadViewer.Box first = panes.get(0).box();
        LiveHeadViewer.Box second = panes.get(1).box();
        assertFalse(panes.get(0).second());
        assertTrue(panes.get(1).second());
        assertEquals(LiveHeadViewer.layout(480, 160), first);
        assertEquals(first.x(), second.x());
        assertEquals(first.y() + first.h() + 2 * LiveHeadViewer.BORDER + HeadViewerPanes.GAP, second.y());
        assertEquals(first.w(), second.w());
        assertEquals(first.h(), second.h());
    }

    @Test
    @DisplayName("at the largest box size only one pane shows, the chosen stream or the first")
    void maxSizeShowsOnePane() {
        assertEquals(1, HeadViewerPanes.current(480, H, 240, true, true).size());
        assertFalse(HeadViewerPanes.current(480, H, 240, true, true).get(0).second());
        assertEquals(2, HeadViewerPanes.current(480, H, 160, true, false).size());
    }

    @Test
    @DisplayName("solo: the chosen stream alone, in the first pane's spot")
    void soloTakesTheFirstSpot() {
        List<HeadViewerPanes.Pane> main = HeadViewerPanes.panes(480, H, 160, true, false, MAIN_ONLY);
        assertEquals(List.of(new HeadViewerPanes.Pane(false, LiveHeadViewer.layout(480, 160))), main);
        List<HeadViewerPanes.Pane> second = HeadViewerPanes.panes(480, H, 160, true, false, SECOND_ONLY);
        assertEquals(List.of(new HeadViewerPanes.Pane(true, LiveHeadViewer.layout(480, 160))), second);
    }

    @Test
    @DisplayName("a click on a pane solos it; a click on the lone pane brings both back; elsewhere nothing")
    void clicksSoloAndRestore() {
        List<HeadViewerPanes.Pane> both = HeadViewerPanes.panes(480, H, 160, true, false, BOTH);
        LiveHeadViewer.Box first = both.get(0).box();
        LiveHeadViewer.Box second = both.get(1).box();
        assertEquals(MAIN_ONLY, HeadViewerPanes.click(both, BOTH, first.x() + 5, first.y() + 5));
        assertEquals(SECOND_ONLY, HeadViewerPanes.click(both, BOTH, second.x() + 5, second.y() + 5));
        assertEquals(BOTH, HeadViewerPanes.click(both, BOTH, 2, 2), "outside: unchanged");
        List<HeadViewerPanes.Pane> solo = HeadViewerPanes.panes(480, H, 160, true, false, SECOND_ONLY);
        assertEquals(BOTH, HeadViewerPanes.click(solo, SECOND_ONLY, first.x() + 5, first.y() + 5));
        assertEquals(SECOND_ONLY, HeadViewerPanes.click(solo, SECOND_ONLY, second.x() + 5, second.y() + 5),
            "the empty left spot is not a pane any more");
    }

    @Test
    @DisplayName("the session choice is applied by clicks and forgotten when the second stream goes dark")
    void sessionChoiceFollowsClicksAndResets() {
        List<HeadViewerPanes.Pane> both = HeadViewerPanes.current(480, H, 160, true, false);
        assertEquals(2, both.size());
        LiveHeadViewer.Box second = both.get(1).box();
        assertTrue(HeadViewerPanes.apply(both, second.x() + 5, second.y() + 5, 0L));
        assertEquals(SECOND_ONLY, HeadViewerPanes.choice());
        assertEquals(1, HeadViewerPanes.current(480, H, 160, true, false).size());
        assertFalse(HeadViewerPanes.apply(HeadViewerPanes.current(480, H, 160, true, false), 2, 2, 0L), "a miss changes nothing");
        HeadViewerPanes.current(480, H, 160, false, false);
        assertEquals(BOTH, HeadViewerPanes.choice(), "one stream: back to both for the next pair");
    }

    private static games.brennan.dungeontrain.config.LiveFeedClientConfig.WornTvStreams opt(String name) {
        return Enum.valueOf(games.brennan.dungeontrain.config.LiveFeedClientConfig.WornTvStreams.class, name);
    }

    @Test
    @DisplayName("a worn TV's memory picks the choice; without one the settings default does")
    void memoryAndOptionPickTheChoice() {
        assertEquals(MAIN_ONLY, HeadViewerPanes.choiceFor(1, opt("BOTH")));
        assertEquals(SECOND_ONLY, HeadViewerPanes.choiceFor(2, opt("STREAM_1")), "memory wins over the option");
        assertEquals(BOTH, HeadViewerPanes.choiceFor(0, opt("BOTH")));
        assertEquals(MAIN_ONLY, HeadViewerPanes.choiceFor(0, opt("STREAM_1")));
        assertEquals(SECOND_ONLY, HeadViewerPanes.choiceFor(0, opt("STREAM_2")));
        assertEquals(0, HeadViewerPanes.memoryOf(BOTH));
        assertEquals(1, HeadViewerPanes.memoryOf(MAIN_ONLY));
        assertEquals(2, HeadViewerPanes.memoryOf(SECOND_ONLY));
        HeadViewerPanes.wearing(true, 2, opt("BOTH"), 0L);
        assertEquals(SECOND_ONLY, HeadViewerPanes.choice());
        HeadViewerPanes.wearing(true, 1, opt("BOTH"), 10L);
        assertEquals(MAIN_ONLY, HeadViewerPanes.choice(), "a different TV put on follows its memory");
    }

    @Test
    @DisplayName("a solo click holds until the server echoes it: no frame flicks back to both")
    void clickHoldsUntilTheEcho() {
        HeadViewerPanes.wearing(true, 0, opt("BOTH"), 0L);
        List<HeadViewerPanes.Pane> both = HeadViewerPanes.current(480, H, 160, true, false);
        LiveHeadViewer.Box second = both.get(1).box();
        assertTrue(HeadViewerPanes.apply(both, second.x() + 5, second.y() + 5, 1_000L));
        HeadViewerPanes.wearing(true, 0, opt("BOTH"), 1_050L);
        assertEquals(SECOND_ONLY, HeadViewerPanes.choice(), "the TV still says 0 for a round trip: held");
        HeadViewerPanes.wearing(true, 2, opt("BOTH"), 1_100L);
        assertEquals(SECOND_ONLY, HeadViewerPanes.choice(), "the echo confirms it");
        HeadViewerPanes.wearing(true, 2, opt("BOTH"), 9_000L);
        assertEquals(SECOND_ONLY, HeadViewerPanes.choice());
    }

    @Test
    @DisplayName("two quick clicks: the first click's echo does not undo the second")
    void intermediateEchoIgnored() {
        HeadViewerPanes.wearing(true, 0, opt("BOTH"), 0L);
        List<HeadViewerPanes.Pane> both = HeadViewerPanes.current(480, H, 160, true, false);
        LiveHeadViewer.Box second = both.get(1).box();
        assertTrue(HeadViewerPanes.apply(both, second.x() + 5, second.y() + 5, 1_000L));
        List<HeadViewerPanes.Pane> solo = HeadViewerPanes.current(480, H, 160, true, false);
        assertTrue(HeadViewerPanes.apply(solo, solo.get(0).box().x() + 5, solo.get(0).box().y() + 5, 1_020L));
        assertEquals(BOTH, HeadViewerPanes.choice());
        HeadViewerPanes.wearing(true, 2, opt("BOTH"), 1_100L);
        assertEquals(BOTH, HeadViewerPanes.choice(), "the stale echo of the first click is ignored");
        HeadViewerPanes.wearing(true, 0, opt("BOTH"), 1_150L);
        assertEquals(BOTH, HeadViewerPanes.choice());
    }

    @Test
    @DisplayName("no echo (a creative screen undid it): after the timeout the click stays")
    void noEchoKeepsTheClick() {
        HeadViewerPanes.wearing(true, 0, opt("BOTH"), 0L);
        List<HeadViewerPanes.Pane> both = HeadViewerPanes.current(480, H, 160, true, false);
        assertTrue(HeadViewerPanes.apply(both, both.get(0).box().x() + 5, both.get(0).box().y() + 5, 1_000L));
        HeadViewerPanes.wearing(true, 0, opt("BOTH"), 1_000L + HeadViewerPanes.PENDING_MS + 1);
        assertEquals(MAIN_ONLY, HeadViewerPanes.choice());
        HeadViewerPanes.wearing(true, 2, opt("BOTH"), 5_000L);
        assertEquals(SECOND_ONLY, HeadViewerPanes.choice(), "a different TV put on afterwards is followed");
    }

    @Test
    @DisplayName("a Live Eye has no memory: a solo click sticks for the session")
    void liveEyeClickSticks() {
        HeadViewerPanes.wearing(false, 0, opt("BOTH"), 0L);
        assertEquals(BOTH, HeadViewerPanes.choice());
        List<HeadViewerPanes.Pane> both = HeadViewerPanes.current(480, H, 160, true, false);
        LiveHeadViewer.Box second = both.get(1).box();
        assertTrue(HeadViewerPanes.apply(both, second.x() + 5, second.y() + 5, 1_000L));
        for (long t = 1_000L; t < 10_000L; t += 500L) {
            HeadViewerPanes.wearing(false, 0, opt("BOTH"), t);
            assertEquals(SECOND_ONLY, HeadViewerPanes.choice(), "at " + t);
        }
    }

    @Test
    @DisplayName("a window too short for two panes and the takeover button: one pane, the chosen or the first")
    void shortWindowShowsOnePane() {
        // 160 wide: 90 tall each; second pane ends at 8+90+2+8+90 = 198, button to 198+1+3+20 = 222.
        assertTrue(HeadViewerPanes.twoFit(480, 222, 160, false, 16f / 9f, 16f / 9f));
        assertFalse(HeadViewerPanes.twoFit(480, 221, 160, false, 16f / 9f, 16f / 9f));
        assertFalse(HeadViewerPanes.twoFit(480, 2000, 160, true, 16f / 9f, 16f / 9f), "largest size never stacks");
        List<HeadViewerPanes.Pane> shortBoth = HeadViewerPanes.panes(480, 221, 160, true, false, BOTH);
        assertEquals(List.of(new HeadViewerPanes.Pane(false, LiveHeadViewer.layout(480, 160))), shortBoth);
        List<HeadViewerPanes.Pane> shortSecond = HeadViewerPanes.panes(480, 221, 160, true, false, SECOND_ONLY);
        assertEquals(List.of(new HeadViewerPanes.Pane(true, LiveHeadViewer.layout(480, 160))), shortSecond);
        for (HeadViewerPanes.Pane pane : HeadViewerPanes.panes(480, 222, 160, true, false, BOTH)) {
            assertTrue(pane.box().y() + pane.box().h() <= 222);
        }
    }

    @Test
    @DisplayName("at the largest size a soloed second stream is the one pane shown")
    void maxSizeShowsTheChosenStream() {
        List<HeadViewerPanes.Pane> panes = HeadViewerPanes.panes(480, H, 240, true, true, SECOND_ONLY);
        assertEquals(1, panes.size());
        assertTrue(panes.get(0).second());
    }

    @Test
    void eachPaneIsAsTallAsItsOwnStream() {
        // A 4:3 first stream and a 16:9 second, both 160 wide: 120 and 90 tall.
        List<HeadViewerPanes.Pane> both = HeadViewerPanes.panes(480, H, 160, true, false, BOTH, 4f / 3f, 16f / 9f);
        assertEquals(120, both.get(0).box().h());
        assertEquals(90, both.get(1).box().h());
        assertEquals(both.get(0).box().y() + 120 + 2 * LiveHeadViewer.BORDER + HeadViewerPanes.GAP, both.get(1).box().y(),
            "the second sits under the taller first");
        List<HeadViewerPanes.Pane> solo = HeadViewerPanes.panes(480, H, 160, true, false, SECOND_ONLY, 4f / 3f, 16f / 9f);
        assertEquals(90, solo.get(0).box().h(), "a soloed second takes its own shape in the first spot");
    }
}
