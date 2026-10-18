package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The void-band windows {@link DistantHorizonsSuppression} hides Distant Horizons across. */
final class ClientVoidBandDhZonesTest {

    /**
     * WorldGenCycleTest#stacksBand's layout: chuncks fade [3390,3590) core [3590,4090); spheres fade
     * [4190,4390) core [4390,4790); stacks fade [4890,5090) core [5090,5490); period 4490.
     */
    private static final WorldGenCycle C = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200,
            100, 40, 200, 50, 200, 150, 0, 500, 200, 0, 0.12, 0.5, 400, 200, 100, 400, 200, 100, 0.08, 0);

    /** No chuncks, spheres or stacks band at all. */
    private static final WorldGenCycle OFF =
            new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 0, 0, 0, 0);

    @Test
    @DisplayName("chuncks: fade and core hide DH; the overworld before does not; the margin reaches it")
    void chuncks() {
        assertTrue(ClientVoidBand.chuncksZoneWithin(C, 3450, 0));
        assertTrue(ClientVoidBand.chuncksZoneWithin(C, 3800, 0));
        assertFalse(ClientVoidBand.chuncksZoneWithin(C, 3300, 0));
        assertTrue(ClientVoidBand.chuncksZoneWithin(C, 3300, 100));
        assertFalse(ClientVoidBand.chuncksZoneWithin(C, 3200, 100));
    }

    @Test
    @DisplayName("spheres: fade and core hide DH; the gaps either side do not; the margin reaches it")
    void spheres() {
        assertTrue(ClientVoidBand.spheresZoneWithin(C, 4300, 0));
        assertTrue(ClientVoidBand.spheresZoneWithin(C, 4600, 0));
        assertFalse(ClientVoidBand.spheresZoneWithin(C, 4150, 0));
        assertFalse(ClientVoidBand.spheresZoneWithin(C, 4840, 0));
        assertTrue(ClientVoidBand.spheresZoneWithin(C, 4840, 100));
    }

    @Test
    @DisplayName("stacks: fade and core hide DH; the lead gap and the next period do not; the margin reaches it")
    void stacks() {
        assertTrue(ClientVoidBand.stacksZoneWithin(C, 4950, 0));
        assertTrue(ClientVoidBand.stacksZoneWithin(C, 5300, 0));
        assertFalse(ClientVoidBand.stacksZoneWithin(C, 4850, 0));
        assertFalse(ClientVoidBand.stacksZoneWithin(C, 5600, 0));
        assertTrue(ClientVoidBand.stacksZoneWithin(C, 5550, 100));
    }

    @Test
    @DisplayName("a cycle without the bands never hides DH")
    void disabled() {
        assertFalse(ClientVoidBand.chuncksZoneWithin(OFF, 3800, 1024));
        assertFalse(ClientVoidBand.spheresZoneWithin(OFF, 4600, 1024));
        assertFalse(ClientVoidBand.stacksZoneWithin(OFF, 5300, 1024));
    }
}
