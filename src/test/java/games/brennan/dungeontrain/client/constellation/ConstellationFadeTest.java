package games.brennan.dungeontrain.client.constellation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstellationFadeTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void opensOverFadeSecondsAndClosesAgain() {
        ConstellationFade fade = new ConstellationFade();
        long t = 0;
        fade.advance(true, t);
        assertFalse(fade.visible(), "first frame only starts the clock");
        // 0.1 s steps (each under the stall cap) for the full fade
        for (int i = 0; i < 12; i++) fade.advance(true, t += SECOND / 10);
        assertEquals(1f, fade.eased(), 1e-4f);
        for (int i = 0; i < 6; i++) fade.advance(false, t += SECOND / 10);
        assertEquals(0.5f, fade.eased(), 1e-3f, "smoothstep is symmetric: halfway out is half strength");
        for (int i = 0; i < 6; i++) fade.advance(false, t += SECOND / 10);
        assertFalse(fade.visible());
    }

    @Test
    void aStallDoesNotCountAsFadeTime() {
        ConstellationFade fade = new ConstellationFade();
        fade.advance(true, 0);
        fade.advance(true, 30 * SECOND); // a 30 s pause
        assertTrue(fade.eased() < 0.2f, "capped at a quarter second: " + fade.eased());
    }

    @Test
    void resetClosesAtOnce() {
        ConstellationFade fade = new ConstellationFade();
        long t = 0;
        fade.advance(true, t);
        for (int i = 0; i < 12; i++) fade.advance(true, t += SECOND / 10);
        fade.reset();
        assertEquals(0f, fade.eased());
    }
}
