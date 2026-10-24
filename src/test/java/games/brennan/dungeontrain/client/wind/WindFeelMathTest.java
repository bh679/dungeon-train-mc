package games.brennan.dungeontrain.client.wind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindFeelMathTest {

    private static final double EPS = 1.0e-9;

    @Test
    void feltIsZeroInACalmAndCapsAtOne() {
        assertEquals(0.0, WindFeelMath.feltTarget(0.0), EPS);
        assertEquals(0.0, WindFeelMath.feltTarget(-0.01), EPS);
        assertEquals(0.0, WindFeelMath.feltTarget(WindFeelMath.CALM_FLOOR), EPS);
        assertEquals(1.0, WindFeelMath.feltTarget(WindFeelMath.FELT_FULL), EPS);
        assertEquals(1.0, WindFeelMath.feltTarget(1.0), EPS);
    }

    @Test
    void faintWindIsAlreadyFeltUnderGrip() {
        // Windy 0.08 is a push of 0.0016: below a standing player's grip, but it should read clearly.
        double f = WindFeelMath.feltTarget(0.0016);
        assertTrue(f > 0.5 && f < 0.6, "felt at Windy 0.08 was " + f);
        assertTrue(WindFeelMath.feltTarget(0.0008) < f, "felt should rise with the wind");
    }

    @Test
    void calmFadeIsZeroInACalmAndOneOnceTheWindIsUp() {
        assertEquals(0.0, WindFeelMath.calmFade(0.0), EPS);
        assertEquals(0.0, WindFeelMath.calmFade(WindFeelMath.CALM_FLOOR), EPS);
        assertEquals(1.0, WindFeelMath.calmFade(WindFeelMath.FADE_FULL), EPS);
        assertEquals(1.0, WindFeelMath.calmFade(0.02), EPS);
        double mid = WindFeelMath.calmFade((WindFeelMath.CALM_FLOOR + WindFeelMath.FADE_FULL) / 2);
        assertEquals(0.5, mid, EPS);
    }

    @Test
    void feltRisesFasterThanItFalls() {
        double up = WindFeelMath.easeFelt(0.0, 1.0);
        double down = 1.0 - WindFeelMath.easeFelt(1.0, 0.0);
        assertTrue(up > down, "rise " + up + " should beat fall " + down);
    }

    @Test
    void feltSettlesToExactlyZero() {
        double f = 1.0;
        for (int i = 0; i < 200; i++) f = WindFeelMath.easeFelt(f, 0.0);
        assertEquals(0.0, f, 0.0);
    }

    @Test
    void lateralFollowsTheScreen() {
        // Facing +Z (yaw 0): the right of the screen is -X.
        assertEquals(1.0, WindFeelMath.lateral(-1, 0, 0), EPS);
        assertEquals(-1.0, WindFeelMath.lateral(1, 0, 0), EPS);
        assertEquals(0.0, WindFeelMath.lateral(0, 1, 0), EPS);
        // Facing -X (yaw 90): the right of the screen is -Z.
        assertEquals(1.0, WindFeelMath.lateral(0, -1, 90), EPS);
        // Facing +X along the train (yaw -90): the default +Z wind blows toward the right of the screen.
        assertEquals(1.0, WindFeelMath.lateral(0, 1, -90), EPS);
    }

    @Test
    void facingIsPositiveFromBehind() {
        assertEquals(1.0, WindFeelMath.facing(0, 1, 0), EPS);
        assertEquals(-1.0, WindFeelMath.facing(0, -1, 0), EPS);
        assertEquals(0.0, WindFeelMath.facing(1, 0, 0), EPS);
    }
}
