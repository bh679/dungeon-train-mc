package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The camcorder is consumed only when the feed is taken from the wearer. */
final class LiveFeedEventsTest {

    @Test
    @DisplayName("replaced, died and cut off burn the camcorder; removed, left and failed keep it")
    void whichExitsBurn() {
        assertTrue(LiveFeedEvents.burnsOn(LiveFeedEvents.Exit.REPLACED));
        assertTrue(LiveFeedEvents.burnsOn(LiveFeedEvents.Exit.DIED));
        assertTrue(LiveFeedEvents.burnsOn(LiveFeedEvents.Exit.CUT_OFF));
        assertFalse(LiveFeedEvents.burnsOn(LiveFeedEvents.Exit.REMOVED));
        assertFalse(LiveFeedEvents.burnsOn(LiveFeedEvents.Exit.LEFT));
        assertFalse(LiveFeedEvents.burnsOn(LiveFeedEvents.Exit.FAILED));
    }
}
