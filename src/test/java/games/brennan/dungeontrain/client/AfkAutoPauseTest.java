package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.AfkAutoPause.IDLE_PAUSE_MS;
import static games.brennan.dungeontrain.client.AfkAutoPause.IDLE_PAUSE_SCREEN_MS;
import static games.brennan.dungeontrain.client.AfkAutoPause.lookMoved;
import static games.brennan.dungeontrain.client.AfkAutoPause.shouldPause;
import static games.brennan.dungeontrain.client.AfkAutoPause.thresholdMs;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic coverage for {@link AfkAutoPause}. */
final class AfkAutoPauseTest {

    private static final long SECOND = 1_000L;

    /** In the world, no screen, setting on, nothing suppressing it. */
    private static boolean inWorld(long idleMs) {
        return shouldPause(idleMs, false, false, false, true, false, false);
    }

    /** A book, chest, inventory or photo open. */
    private static boolean onScreen(long idleMs) {
        return shouldPause(idleMs, true, false, false, true, false, false);
    }

    @Test
    @DisplayName("thresholds are 2 minutes in the world and 5 minutes on a screen")
    void thresholds() {
        assertEquals(120 * SECOND, IDLE_PAUSE_MS);
        assertEquals(300 * SECOND, IDLE_PAUSE_SCREEN_MS);
        assertEquals(IDLE_PAUSE_MS, thresholdMs(false));
        assertEquals(IDLE_PAUSE_SCREEN_MS, thresholdMs(true));
    }

    @Test
    @DisplayName("in the world: no pause before 2 minutes idle, pause at 2 minutes")
    void inWorldWaitsTwoMinutes() {
        assertFalse(inWorld(30 * SECOND));
        assertFalse(inWorld(119 * SECOND));
        assertTrue(inWorld(120 * SECOND));
        assertTrue(inWorld(600 * SECOND));
    }

    @Test
    @DisplayName("on a screen: no pause before 5 minutes idle, pause at 5 minutes")
    void onScreenWaitsFiveMinutes() {
        assertFalse(onScreen(120 * SECOND));
        assertFalse(onScreen(299 * SECOND));
        assertTrue(onScreen(300 * SECOND));
    }

    @Test
    @DisplayName("once per idle spell")
    void oncePerSpell() {
        assertFalse(shouldPause(600 * SECOND, false, false, true, true, false, false));
        assertFalse(shouldPause(600 * SECOND, true, false, true, true, false, false));
    }

    @Test
    @DisplayName("never over the pause menu or its settings")
    void notOverPauseMenu() {
        assertFalse(shouldPause(600 * SECOND, true, true, false, true, false, false));
    }

    @Test
    @DisplayName("disabled, Free Play, or VR suppresses the pause")
    void suppressed() {
        assertFalse(shouldPause(600 * SECOND, false, false, false, false, false, false));
        assertFalse(shouldPause(600 * SECOND, false, false, false, true, true, false));
        assertFalse(shouldPause(600 * SECOND, false, false, false, true, false, true));
    }

    @Test
    @DisplayName("camera turns past half a degree count as the mouse, on the shortest arc")
    void lookMovement() {
        assertFalse(lookMoved(Float.NaN, Float.NaN, 10f, 0f));
        assertFalse(lookMoved(10f, 0f, 10.4f, 0.4f));
        assertTrue(lookMoved(10f, 0f, 11f, 0f));
        assertTrue(lookMoved(10f, 0f, 10f, -1f));
        assertFalse(lookMoved(359.8f, 0f, 0.1f, 0f));
        assertTrue(lookMoved(359f, 0f, 1f, 0f));
    }
}
