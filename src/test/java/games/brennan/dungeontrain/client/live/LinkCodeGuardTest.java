package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.client.live.LinkCodeGuard.HUD_TICKS;
import static games.brennan.dungeontrain.client.live.LinkCodeGuard.onHud;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A link code printed to chat keeps chat off the stream for as long as the HUD can show it. */
class LinkCodeGuardTest {

    @Test
    void heldWhileTheLineCanBeOnTheHud() {
        assertTrue(onHud(1000, 1000));
        assertTrue(onHud(1000 + 200, 1000)); // vanilla's fade point
        assertTrue(onHud(1000 + HUD_TICKS - 1, 1000));
    }

    @Test
    void releasedOnceItHasFaded() {
        assertFalse(onHud(1000 + HUD_TICKS, 1000));
        assertFalse(onHud(5000, 1000));
    }

    @Test
    void neverShownIsNeverHeld() {
        assertFalse(onHud(0, Long.MIN_VALUE));
        assertFalse(onHud(Long.MAX_VALUE, Long.MIN_VALUE));
    }

    @Test
    void aClockThatWentBackIsNotHeld() {
        assertFalse(onHud(10, 1000));
    }
}
