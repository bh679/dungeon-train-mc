package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefocusClickGuardTest {

    @BeforeEach
    void fresh() {
        RefocusClickGuard.reset();
    }

    @Test
    void aFocusedWindowNeverSwallows() {
        for (int i = 0; i < 100; i++) RefocusClickGuard.tick(true);
        assertFalse(RefocusClickGuard.swallow());
    }

    @Test
    void theClickThatBringsTheWindowBackIsSwallowedThenClicksCount() {
        for (int i = 0; i < 20; i++) RefocusClickGuard.tick(true);
        for (int i = 0; i < 200; i++) RefocusClickGuard.tick(false);
        assertFalse(RefocusClickGuard.swallow(), "unfocused: nothing to swallow yet");
        RefocusClickGuard.tick(true);
        assertTrue(RefocusClickGuard.swallow(), "the activating click");
        for (int i = 0; i < RefocusClickGuard.GRACE_TICKS - 2; i++) RefocusClickGuard.tick(true);
        assertTrue(RefocusClickGuard.swallow(), "still inside the grace");
        for (int i = 0; i < 2; i++) RefocusClickGuard.tick(true);
        assertFalse(RefocusClickGuard.swallow(), "after the grace, clicks are the player's");
    }
}
