package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for {@link NetherFade#selectsNether} — the per-block Overworld↔Nether dither that
 * composites tunnel/track variants across the Nether crossfade. The stamp-time wiring (mask
 * processor, {@code TilePaint} composite) needs a live {@code ServerLevel} and is covered by the
 * in-game Gate-2 test, the same split {@link TrainPhaseTest} uses for {@code phaseAt}.
 */
final class NetherFadeTest {

    private static final long SEED = 0x123456789ABCDEFL;

    @Test
    @DisplayName("ramp <= 0 is never Nether (pure overworld approach)")
    void belowCrossfaceNeverNether() {
        for (double ramp : new double[] {0.0, -0.5}) {
            for (int x = 0; x < 64; x++) {
                assertFalse(NetherFade.selectsNether(SEED, x, 70, 0, ramp), "x=" + x + " ramp=" + ramp);
            }
        }
    }

    @Test
    @DisplayName("ramp >= 1 is always Nether (real-Nether core)")
    void aboveCrossfaceAlwaysNether() {
        for (double ramp : new double[] {1.0, 1.5}) {
            for (int x = 0; x < 64; x++) {
                assertTrue(NetherFade.selectsNether(SEED, x, 70, 0, ramp), "x=" + x + " ramp=" + ramp);
            }
        }
    }

    @Test
    @DisplayName("Inside the crossfade the decision is exactly coherentNoise < ramp")
    void insideCrossfaceMatchesNoise() {
        for (double ramp : new double[] {0.1, 0.3, 0.5, 0.7, 0.9}) {
            for (int x = -40; x < 40; x++) {
                for (int y = 60; y < 76; y += 5) {
                    boolean expected = Disintegration.coherentNoise(SEED ^ NetherFade.SALT, x, y, 0) < ramp;
                    assertEquals(expected, NetherFade.selectsNether(SEED, x, y, 0, ramp),
                        "x=" + x + " y=" + y + " ramp=" + ramp);
                }
            }
        }
    }

    @Test
    @DisplayName("Deterministic in (seed, x, y, z, ramp) — same inputs, same answer")
    void deterministic() {
        for (int x = 0; x < 200; x++) {
            boolean a = NetherFade.selectsNether(SEED, x, 64, x % 7, 0.5);
            boolean b = NetherFade.selectsNether(SEED, x, 64, x % 7, 0.5);
            assertEquals(a, b, "x=" + x);
        }
    }

    @Test
    @DisplayName("Nether fraction rises with the ramp (more cells flip as the core nears)")
    void fractionTracksRamp() {
        int prev = -1;
        for (double ramp : new double[] {0.1, 0.3, 0.5, 0.7, 0.9}) {
            int count = 0;
            for (int x = -200; x < 200; x++) {
                if (NetherFade.selectsNether(SEED, x, 64, 0, ramp)) count++;
            }
            assertTrue(count >= prev, "Nether count should be monotonic in ramp; ramp=" + ramp);
            prev = count;
        }
    }
}
