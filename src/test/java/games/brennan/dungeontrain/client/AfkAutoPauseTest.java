package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.event.PlayerActivityTracker.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.AfkAutoPause.shouldPause;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic coverage for {@link AfkAutoPause#shouldPause}. */
final class AfkAutoPauseTest {

    private static final int NONE = -1;
    private static final int TRACKING = Reason.TRACKING.ordinal();
    private static final int PAUSED = Reason.PAUSED.ordinal();
    private static final int MOUSE_IDLE = Reason.MOUSE_IDLE.ordinal();
    private static final int INPUT_IDLE = Reason.INPUT_IDLE.ordinal();
    private static final int NO_PROGRESS = Reason.NO_PROGRESS.ordinal();

    @Test
    @DisplayName("pauses on the edge into either AFK reason")
    void pausesOnEdgeIntoAfk() {
        assertTrue(shouldPause(TRACKING, MOUSE_IDLE, true, false, false));
        assertTrue(shouldPause(TRACKING, INPUT_IDLE, true, false, false));
        assertTrue(shouldPause(NO_PROGRESS, MOUSE_IDLE, true, false, false));
        assertTrue(shouldPause(NONE, MOUSE_IDLE, true, false, false));
    }

    @Test
    @DisplayName("does not re-pause while AFK persists or moves between AFK reasons")
    void edgeOnly() {
        assertFalse(shouldPause(MOUSE_IDLE, MOUSE_IDLE, true, false, false));
        assertFalse(shouldPause(MOUSE_IDLE, INPUT_IDLE, true, false, false));
        assertFalse(shouldPause(INPUT_IDLE, MOUSE_IDLE, true, false, false));
    }

    @Test
    @DisplayName("non-AFK reasons never pause")
    void nonAfkNeverPauses() {
        assertFalse(shouldPause(TRACKING, TRACKING, true, false, false));
        assertFalse(shouldPause(TRACKING, PAUSED, true, false, false));
        assertFalse(shouldPause(TRACKING, NO_PROGRESS, true, false, false));
    }

    @Test
    @DisplayName("disabled, screen open, or VR suppresses the pause")
    void suppressed() {
        assertFalse(shouldPause(TRACKING, MOUSE_IDLE, false, false, false));
        assertFalse(shouldPause(TRACKING, MOUSE_IDLE, true, true, false));
        assertFalse(shouldPause(TRACKING, MOUSE_IDLE, true, false, true));
    }
}
