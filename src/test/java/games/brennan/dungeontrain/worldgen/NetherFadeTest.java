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
    @DisplayName("Inside the crossfade each cell flips Overworld→Nether exactly once as the ramp rises")
    void perCellMonotonicThreshold() {
        // selectsNether is `noise < ramp` for a fixed (hidden) per-cell noise value, so for any cell
        // the answer is monotone non-decreasing in ramp: once true it never reverts to false. Verifies
        // the dither contract without depending on the noise function's internals.
        for (int x : new int[] {-37, -8, 0, 5, 41, 128}) {
            for (int z : new int[] {0, 3}) {
                boolean prev = false;
                for (int i = 0; i <= 100; i++) {
                    boolean cur = NetherFade.selectsNether(SEED, x, 64, z, i / 100.0);
                    assertTrue(!prev || cur, "non-monotonic at x=" + x + " z=" + z + " ramp=" + (i / 100.0));
                    prev = cur;
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
