package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.event.PlayerActivityTracker.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules behind "is this player actually playing, and are they getting anywhere?" — the two idle
 * clocks, what counts as a look change, the carriage-traversal measure, and which reasons stop
 * which counter. All pure statics over plain arguments, so no server or player is needed; the
 * wiring around them (event subscriptions, the paused set, the HUD packet) is exercised in-game.
 */
class PlayerActivityTrackerTest {

    private static final long LOOK = PlayerActivityTracker.LOOK_IDLE_TICKS;
    private static final long INPUT = PlayerActivityTracker.INPUT_IDLE_TICKS;
    private static final float EPS = 0.5f;

    private static Deque<long[]> history(long[]... samples) {
        Deque<long[]> deque = new ArrayDeque<>();
        for (long[] sample : samples) deque.addLast(sample);
        return deque;
    }

    // ------------------------------------------------------------- thresholds

    @Test
    @DisplayName("the mouse clock is 30 seconds and the input clock is 5 minutes")
    void thresholdsAreThirtySecondsAndFiveMinutes() {
        assertEquals(30L * 20L, LOOK);
        assertEquals(5L * 60L * 20L, INPUT);
        assertEquals(10L * 60L * 20L, PlayerActivityTracker.PROGRESS_WINDOW_TICKS);
        assertEquals(3, PlayerActivityTracker.MIN_CARRIAGES_PER_WINDOW);
    }

    @Test
    @DisplayName("just under a threshold is still active; exactly at it is idle")
    void thresholdBoundaries() {
        assertFalse(PlayerActivityTracker.isIdle(1_000L, 1_000L + LOOK - 1L, LOOK));
        assertTrue(PlayerActivityTracker.isIdle(1_000L, 1_000L + LOOK, LOOK));
        assertFalse(PlayerActivityTracker.isIdle(1_000L, 1_000L + INPUT - 1L, INPUT));
        assertTrue(PlayerActivityTracker.isIdle(1_000L, 1_000L + INPUT, INPUT));
    }

    @Test
    @DisplayName("activity stamped this tick is active")
    void sameTickIsActive() {
        assertFalse(PlayerActivityTracker.isIdle(5_000L, 5_000L, LOOK));
    }

    @Test
    @DisplayName("a player looking around for four minutes has still gone quiet on the input clock")
    void lookingAroundDoesNotFeedTheInputClock() {
        // The whole point of two clocks: at tick 5000 the mouse is fresh but no key has been
        // pressed since tick 0, so only the input rule can catch this player — at 6000.
        assertFalse(PlayerActivityTracker.isIdle(4_990L, 5_000L, LOOK));
        assertFalse(PlayerActivityTracker.isIdle(0L, 5_000L, INPUT));
        assertTrue(PlayerActivityTracker.isIdle(0L, 6_000L, INPUT));
    }

    // ----------------------------------------------------------- look changes

    @Test
    @DisplayName("mouse jitter below the epsilon is not a look change")
    void jitterIsNotALookChange() {
        assertFalse(PlayerActivityTracker.lookChanged(90.0f, 10.0f, 90.2f, 10.3f, EPS));
    }

    @Test
    @DisplayName("a yaw or pitch glance past the epsilon is a look change")
    void glanceIsALookChange() {
        assertTrue(PlayerActivityTracker.lookChanged(90.0f, 10.0f, 95.0f, 10.0f, EPS));
        assertTrue(PlayerActivityTracker.lookChanged(90.0f, 10.0f, 90.0f, 25.0f, EPS));
    }

    @Test
    @DisplayName("359 degrees to 1 degree reads as a 2-degree turn, not 358")
    void yawWrapIsShortestArc() {
        assertTrue(PlayerActivityTracker.lookChanged(359.0f, 0.0f, 1.0f, 0.0f, EPS));
        assertFalse(PlayerActivityTracker.lookChanged(359.0f, 0.0f, 1.0f, 0.0f, 5.0f));
    }

    @Test
    @DisplayName("an unchanged look is not activity")
    void unchangedLookIsNotActivity() {
        assertFalse(PlayerActivityTracker.lookChanged(-140.5f, -3.25f, -140.5f, -3.25f, EPS));
    }

    // ------------------------------------------------------ carriage progress

    @Test
    @DisplayName("carriages traversed is the span of the window, so three forward counts")
    void forwardTraversalCounts() {
        assertEquals(3, PlayerActivityTracker.carriageSpan(
            history(new long[] {0, 10}, new long[] {100, 11}, new long[] {200, 13})));
    }

    @Test
    @DisplayName("three carriages forward and back again is still three traversed")
    void backtrackingStillCounts() {
        assertEquals(3, PlayerActivityTracker.carriageSpan(
            history(new long[] {0, 10}, new long[] {100, 13}, new long[] {200, 10})));
    }

    @Test
    @DisplayName("standing in one carriage all window is no progress")
    void standingStillIsNoProgress() {
        assertEquals(0, PlayerActivityTracker.carriageSpan(
            history(new long[] {0, 42}, new long[] {6_000L, 42}, new long[] {12_000L, 42})));
    }

    @Test
    @DisplayName("negative carriage indices span correctly")
    void negativeIndicesSpan() {
        assertEquals(4, PlayerActivityTracker.carriageSpan(
            history(new long[] {0, -2}, new long[] {100, 2})));
    }

    @Test
    @DisplayName("an empty or absent window spans nothing")
    void emptyWindowSpansNothing() {
        assertEquals(0, PlayerActivityTracker.carriageSpan(history()));
        assertEquals(0, PlayerActivityTracker.carriageSpan(null));
    }

    // ----------------------------------------------------------- the two tiers

    @Test
    @DisplayName("only TRACKING banks time on the train")
    void onlyTrackingBanksTrainTime() {
        assertTrue(PlayerActivityTracker.countsTrain(Reason.TRACKING));
        assertFalse(PlayerActivityTracker.countsTrain(Reason.NO_PROGRESS));
        assertFalse(PlayerActivityTracker.countsTrain(Reason.MOUSE_IDLE));
        assertFalse(PlayerActivityTracker.countsTrain(Reason.INPUT_IDLE));
        assertFalse(PlayerActivityTracker.countsTrain(Reason.PAUSED));
    }

    @Test
    @DisplayName("the HUD reads reasons by ordinal — the wire contract must not drift")
    void reasonOrdinalsMatchTheHudSwitch() {
        assertEquals(0, Reason.TRACKING.ordinal());
        assertEquals(1, Reason.PAUSED.ordinal());
        assertEquals(2, Reason.MOUSE_IDLE.ordinal());
        assertEquals(3, Reason.INPUT_IDLE.ordinal());
        assertEquals(4, Reason.NO_PROGRESS.ordinal());
    }
}
