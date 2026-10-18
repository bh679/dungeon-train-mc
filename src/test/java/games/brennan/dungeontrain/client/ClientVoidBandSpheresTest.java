package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The spheres-band window {@link DistantHorizonsSuppression} hides Distant Horizons across. */
final class ClientVoidBandSpheresTest {

    /** Spheres fade [4490,4690), core [4690,5090) — the layout WorldGenCycleTest#spheresBand pins. */
    private static final WorldGenCycle C = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200,
            100, 40, 200, 50, 200, 150, 0, 500, 200, 300, 0.12, 0.5, 400, 200, 100, 0);

    @Test
    @DisplayName("fade and core hide DH; the lead gap and the next period do not")
    void noMargin() {
        assertTrue(ClientVoidBand.spheresZoneWithin(C, 4600, 0));
        assertTrue(ClientVoidBand.spheresZoneWithin(C, 4900, 0));
        assertFalse(ClientVoidBand.spheresZoneWithin(C, 4400, 0));
        assertFalse(ClientVoidBand.spheresZoneWithin(C, 5200, 0));
    }

    @Test
    @DisplayName("the margin reaches the band from either side")
    void margin() {
        assertTrue(ClientVoidBand.spheresZoneWithin(C, 4400, 100));
        assertTrue(ClientVoidBand.spheresZoneWithin(C, 5150, 100));
        assertFalse(ClientVoidBand.spheresZoneWithin(C, 4300, 100));
    }

    @Test
    @DisplayName("a cycle with no spheres band never hides DH")
    void disabled() {
        WorldGenCycle off = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 0, 0, 0, 0);
        assertFalse(ClientVoidBand.spheresZoneWithin(off, 4900, 1024));
    }
}
