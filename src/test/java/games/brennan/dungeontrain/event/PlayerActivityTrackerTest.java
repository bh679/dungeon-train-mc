package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two decisions behind "is this player actually playing?" — the idle threshold and what counts
 * as a look change. Both are pure static functions taking plain arguments, so no server or player
 * is needed here; the wiring around them (event subscriptions, the paused set) is exercised
 * in-game.
 */
class PlayerActivityTrackerTest {

    private static final long IDLE = PlayerActivityTracker.IDLE_TICKS;
    private static final float EPS = 0.5f;

    @Test
    @DisplayName("just under the threshold is still active")
    void justUnderThresholdIsActive() {
        assertFalse(PlayerActivityTracker.isIdle(1_000L, 1_000L + IDLE - 1L, IDLE));
    }

    @Test
    @DisplayName("exactly at the threshold is idle")
    void atThresholdIsIdle() {
        assertTrue(PlayerActivityTracker.isIdle(1_000L, 1_000L + IDLE, IDLE));
    }

    @Test
    @DisplayName("well past the threshold is idle")
    void pastThresholdIsIdle() {
        assertTrue(PlayerActivityTracker.isIdle(0L, IDLE * 10L, IDLE));
    }

    @Test
    @DisplayName("activity stamped this tick is active")
    void sameTickIsActive() {
        assertFalse(PlayerActivityTracker.isIdle(5_000L, 5_000L, IDLE));
    }

    @Test
    @DisplayName("the threshold is five minutes of server ticks")
    void thresholdIsFiveMinutes() {
        assertTrue(IDLE == 5L * 60L * 20L);
    }

    @Test
    @DisplayName("mouse jitter below the epsilon is not a look change")
    void jitterIsNotALookChange() {
        assertFalse(PlayerActivityTracker.lookChanged(90.0f, 10.0f, 90.2f, 10.3f, EPS));
    }

    @Test
    @DisplayName("a yaw glance past the epsilon is a look change")
    void yawGlanceIsALookChange() {
        assertTrue(PlayerActivityTracker.lookChanged(90.0f, 10.0f, 95.0f, 10.0f, EPS));
    }

    @Test
    @DisplayName("a pitch glance past the epsilon is a look change")
    void pitchGlanceIsALookChange() {
        assertTrue(PlayerActivityTracker.lookChanged(90.0f, 10.0f, 90.0f, 25.0f, EPS));
    }

    @Test
    @DisplayName("359 degrees to 1 degree reads as a 2-degree turn, not 358")
    void yawWrapIsShortestArc() {
        assertTrue(PlayerActivityTracker.lookChanged(359.0f, 0.0f, 1.0f, 0.0f, EPS));
        // ...and the same wrap stays below a threshold wider than the turn.
        assertFalse(PlayerActivityTracker.lookChanged(359.0f, 0.0f, 1.0f, 0.0f, 5.0f));
    }

    @Test
    @DisplayName("an unchanged look is not activity")
    void unchangedLookIsNotActivity() {
        assertFalse(PlayerActivityTracker.lookChanged(-140.5f, -3.25f, -140.5f, -3.25f, EPS));
    }
}
