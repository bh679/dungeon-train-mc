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

    @AfterEach
    void reset() {
        HeadViewerPanes.reset();
    }

    @Test
    @DisplayName("one stream live: the one box in the top-right corner, whatever was chosen before")
    void oneStreamIsTheOldBox() {
        List<HeadViewerPanes.Pane> panes = HeadViewerPanes.panes(480, 160, false, SECOND_ONLY);
        assertEquals(1, panes.size());
        assertFalse(panes.get(0).second());
        assertEquals(LiveHeadViewer.layout(480, 160), panes.get(0).box());
    }

    @Test
    @DisplayName("two streams: the second stream sits to the left of the first, same size and row")
    void twoStreamsSideBySide() {
        List<HeadViewerPanes.Pane> panes = HeadViewerPanes.panes(480, 160, true, BOTH);
        assertEquals(2, panes.size());
        LiveHeadViewer.Box first = panes.get(0).box();
        LiveHeadViewer.Box second = panes.get(1).box();
        assertFalse(panes.get(0).second());
        assertTrue(panes.get(1).second());
        assertEquals(LiveHeadViewer.layout(480, 160), first);
        assertEquals(first.x() - HeadViewerPanes.GAP - 160, second.x());
        assertEquals(first.y(), second.y());
        assertEquals(first.w(), second.w());
        assertEquals(first.h(), second.h());
    }

    @Test
    @DisplayName("solo: the chosen stream alone, in the first pane's spot")
    void soloTakesTheFirstSpot() {
        List<HeadViewerPanes.Pane> main = HeadViewerPanes.panes(480, 160, true, MAIN_ONLY);
        assertEquals(List.of(new HeadViewerPanes.Pane(false, LiveHeadViewer.layout(480, 160))), main);
        List<HeadViewerPanes.Pane> second = HeadViewerPanes.panes(480, 160, true, SECOND_ONLY);
        assertEquals(List.of(new HeadViewerPanes.Pane(true, LiveHeadViewer.layout(480, 160))), second);
    }

    @Test
    @DisplayName("a click on a pane solos it; a click on the lone pane brings both back; elsewhere nothing")
    void clicksSoloAndRestore() {
        List<HeadViewerPanes.Pane> both = HeadViewerPanes.panes(480, 160, true, BOTH);
        LiveHeadViewer.Box first = both.get(0).box();
        LiveHeadViewer.Box second = both.get(1).box();
        assertEquals(MAIN_ONLY, HeadViewerPanes.click(both, BOTH, first.x() + 5, first.y() + 5));
        assertEquals(SECOND_ONLY, HeadViewerPanes.click(both, BOTH, second.x() + 5, second.y() + 5));
        assertEquals(BOTH, HeadViewerPanes.click(both, BOTH, 2, 2), "outside: unchanged");
        List<HeadViewerPanes.Pane> solo = HeadViewerPanes.panes(480, 160, true, SECOND_ONLY);
        assertEquals(BOTH, HeadViewerPanes.click(solo, SECOND_ONLY, first.x() + 5, first.y() + 5));
        assertEquals(SECOND_ONLY, HeadViewerPanes.click(solo, SECOND_ONLY, second.x() + 5, second.y() + 5),
            "the empty left spot is not a pane any more");
    }

    @Test
    @DisplayName("the session choice is applied by clicks and forgotten when the second stream goes dark")
    void sessionChoiceFollowsClicksAndResets() {
        List<HeadViewerPanes.Pane> both = HeadViewerPanes.current(480, 160, true);
        assertEquals(2, both.size());
        LiveHeadViewer.Box second = both.get(1).box();
        assertTrue(HeadViewerPanes.apply(both, second.x() + 5, second.y() + 5));
        assertEquals(SECOND_ONLY, HeadViewerPanes.choice());
        assertEquals(1, HeadViewerPanes.current(480, 160, true).size());
        assertFalse(HeadViewerPanes.apply(HeadViewerPanes.current(480, 160, true), 2, 2), "a miss changes nothing");
        HeadViewerPanes.current(480, 160, false);
        assertEquals(BOTH, HeadViewerPanes.choice(), "one stream: back to both for the next pair");
    }
}
