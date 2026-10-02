package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic unit tests for {@link TestModeEditNoticeEvents#shouldNotify(Long, long)}. Whether the
 * notice reaches a player in a test copy needs a live server and is verified in-game (Gate 2).
 */
final class TestModeEditNoticeEventsTest {

    private static final long REPEAT = TestModeEditNoticeEvents.REPEAT_TICKS;

    @Test
    @DisplayName("first edit in a test always shows the notice")
    void firstEditShows() {
        assertTrue(TestModeEditNoticeEvents.shouldNotify(null, 1000L));
    }

    @Test
    @DisplayName("edits inside the repeat window stay quiet")
    void withinWindowQuiet() {
        assertFalse(TestModeEditNoticeEvents.shouldNotify(1000L, 1000L));
        assertFalse(TestModeEditNoticeEvents.shouldNotify(1000L, 1000L + REPEAT - 1));
    }

    @Test
    @DisplayName("an edit once the window has passed shows it again")
    void afterWindowShows() {
        assertTrue(TestModeEditNoticeEvents.shouldNotify(1000L, 1000L + REPEAT));
    }

    @Test
    @DisplayName("a clock that went backwards shows it rather than going silent")
    void backwardsClockShows() {
        assertTrue(TestModeEditNoticeEvents.shouldNotify(5000L, 100L));
    }
}
