package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.FramerateThrottle.DEFAULT_THROTTLE_FPS;
import static games.brennan.dungeontrain.client.FramerateThrottle.MAX_THROTTLE_FPS;
import static games.brennan.dungeontrain.client.FramerateThrottle.MIN_THROTTLE_FPS;
import static games.brennan.dungeontrain.client.FramerateThrottle.decide;
import static games.brennan.dungeontrain.client.FramerateThrottle.shouldThrottle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for {@link FramerateThrottle} — the idle render cap. Registry-free
 * booleans/ints, so no Minecraft bootstrap; same pattern as
 * {@link games.brennan.dungeontrain.ship.sable.PhysicsFreezeControllerTest}.
 */
final class FramerateThrottleTest {

    /** Vanilla's "Unlimited" sentinel — above this, runTick skips limitDisplayFPS entirely. */
    private static final int UNLIMITED = 260;

    @Test
    @DisplayName("default cap sits inside the configurable range")
    void defaultWithinBounds() {
        assertTrue(DEFAULT_THROTTLE_FPS >= MIN_THROTTLE_FPS);
        assertTrue(DEFAULT_THROTTLE_FPS <= MAX_THROTTLE_FPS);
    }

    /** Non-VR install — the common case. */
    private static final boolean NO_VR = false;

    @Test
    @DisplayName("disabled → never throttles, whatever the state")
    void disabled_passesThrough() {
        assertFalse(shouldThrottle(true, false, false, NO_VR));
        assertEquals(UNLIMITED, decide(true, false, false, NO_VR, DEFAULT_THROTTLE_FPS, UNLIMITED));
        assertEquals(UNLIMITED, decide(true, true, false, NO_VR, DEFAULT_THROTTLE_FPS, UNLIMITED));
    }

    @Test
    @DisplayName("active and unpaused → no throttle (normal play is untouched)")
    void activeUnpaused_passesThrough() {
        assertFalse(shouldThrottle(false, true, true, NO_VR));
        assertEquals(UNLIMITED, decide(false, true, true, NO_VR, DEFAULT_THROTTLE_FPS, UNLIMITED));
    }

    @Test
    @DisplayName("paused → throttles even while the window is focused")
    void paused_throttles() {
        assertTrue(shouldThrottle(true, true, true, NO_VR));
        assertEquals(DEFAULT_THROTTLE_FPS, decide(true, true, true, NO_VR, DEFAULT_THROTTLE_FPS, UNLIMITED));
    }

    @Test
    @DisplayName("unfocused → throttles even while unpaused (covers alt-tab and multiplayer)")
    void unfocused_throttles() {
        assertTrue(shouldThrottle(false, false, true, NO_VR));
        assertEquals(DEFAULT_THROTTLE_FPS, decide(false, false, true, NO_VR, DEFAULT_THROTTLE_FPS, UNLIMITED));
    }

    @Test
    @DisplayName("paused AND unfocused → throttles (no interaction between the two)")
    void pausedAndUnfocused_throttles() {
        assertEquals(DEFAULT_THROTTLE_FPS, decide(true, false, true, NO_VR, DEFAULT_THROTTLE_FPS, UNLIMITED));
    }

    @Test
    @DisplayName("VR → never throttles, in ANY state (capping a headset causes motion sickness)")
    void vr_neverThrottles() {
        // The unfocused case is the dangerous one — the desktop mirror is routinely unfocused
        // while the player is in the headset — but paused is suppressed too: the VR pause menu
        // is still drawn in-headset, so the compositor still needs full-rate frames.
        assertFalse(shouldThrottle(false, false, true, true), "unfocused in VR must not throttle");
        assertFalse(shouldThrottle(true, true, true, true), "paused in VR must not throttle");
        assertFalse(shouldThrottle(true, false, true, true), "paused + unfocused in VR must not throttle");
        assertEquals(UNLIMITED, decide(false, false, true, true, DEFAULT_THROTTLE_FPS, UNLIMITED));
        assertEquals(UNLIMITED, decide(true, false, true, true, DEFAULT_THROTTLE_FPS, UNLIMITED));
    }

    @Test
    @DisplayName("VR suppression beats an explicitly enabled throttle")
    void vr_overridesEnabled() {
        assertTrue(shouldThrottle(true, false, true, NO_VR), "sanity: same state throttles off-VR");
        assertFalse(shouldThrottle(true, false, true, true), "VR must win over enabled=true");
    }

    @Test
    @DisplayName("a user limit BELOW the cap is preserved — the throttle never raises the rate")
    void neverRaisesTheRate() {
        // The regression this guards: a 20 fps player must not be bumped to 30 by pausing.
        assertEquals(20, decide(true, true, true, NO_VR, DEFAULT_THROTTLE_FPS, 20));
        assertEquals(20, decide(false, false, true, NO_VR, DEFAULT_THROTTLE_FPS, 20));
        assertEquals(MIN_THROTTLE_FPS, decide(true, true, true, NO_VR, DEFAULT_THROTTLE_FPS, MIN_THROTTLE_FPS));
    }

    @Test
    @DisplayName("a user limit exactly at the cap is returned unchanged (mixin then skips the override)")
    void equalLimit_isUnchanged() {
        assertEquals(DEFAULT_THROTTLE_FPS,
                decide(true, true, true, NO_VR, DEFAULT_THROTTLE_FPS, DEFAULT_THROTTLE_FPS));
    }

    @Test
    @DisplayName("throttled result is always below vanilla's 260 sentinel, so limitDisplayFPS applies")
    void resultAlwaysBelowUnlimitedSentinel() {
        assertTrue(decide(true, false, true, NO_VR, MAX_THROTTLE_FPS, UNLIMITED) < UNLIMITED,
                "a cap at/above 260 would be silently ignored by runTick");
    }
}
