package games.brennan.dungeontrain.nap;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link HostileBandBeds#inEndBand}: where a Respawn Obelisk works in the overworld's End band. */
final class HostileBandBedsTest {

    /** The same layout as {@code WorldGenCycleTest}: overworld, then the Nether segment, then the End. */
    private static final WorldGenCycle C =
        new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 0, 0, 0, 0);
    private static final int PERIOD = 1940;

    @Test
    @DisplayName("plain overworld and the Nether segment are outside the End band")
    void outsideTheBand() {
        for (int x : new int[] {999, 1000, 1299, 1380, 1530}) {
            assertFalse(HostileBandBeds.inEndBand(C, x), "x=" + x);
        }
    }

    @Test
    @DisplayName("the void hold and the End core are in the band")
    void voidAndCore() {
        assertTrue(HostileBandBeds.inEndBand(C, 2360));
        assertTrue(HostileBandBeds.inEndBand(C, 2500));
    }

    @Test
    @DisplayName("every crumbling column, on both sides of the void, is in the band")
    void crumblingStrip() {
        int crumbling = 0;
        for (int x = 1000; x < 1000 + PERIOD; x++) {
            double ramp = C.endMiddleRamp(x);
            if (ramp > 0.0 && ramp < 1.0) {
                crumbling++;
                assertTrue(HostileBandBeds.inEndBand(C, x), "crumbling x=" + x);
            } else if (ramp == 0.0) {
                assertFalse(HostileBandBeds.inEndBand(C, x), "overworld x=" + x);
            }
        }
        assertTrue(crumbling > 0, "the layout has a crumbling strip");
    }
}
