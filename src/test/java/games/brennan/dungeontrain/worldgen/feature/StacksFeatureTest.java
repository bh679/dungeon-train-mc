package games.brennan.dungeontrain.worldgen.feature;

import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for {@link StacksFeature}'s fit gate and layer arithmetic. Stamping needs a live
 * {@code WorldGenLevel} and is covered by the headless server run (see {@code test-results/}).
 */
final class StacksFeatureTest {

    @Test
    @DisplayName("fits: one-chunk footprint, at least 3 tall, at least 9 blocks of floor; empty templates rejected")
    void fits() {
        assertTrue(StacksFeature.fits(new Vec3i(9, 5, 9)));          // savanna animal pen
        assertTrue(StacksFeature.fits(new Vec3i(16, 6, 16)));        // exactly one chunk
        assertTrue(StacksFeature.fits(new Vec3i(3, 22, 3)));         // bastion bridge leg
        assertFalse(StacksFeature.fits(new Vec3i(17, 6, 10)));       // too wide
        assertFalse(StacksFeature.fits(new Vec3i(10, 6, 17)));       // too deep
        assertFalse(StacksFeature.fits(new Vec3i(10, 2, 10)));       // flat pad
        assertFalse(StacksFeature.fits(new Vec3i(2, 10, 4)));        // sliver (area 8)
        assertFalse(StacksFeature.fits(new Vec3i(0, 0, 0)));         // missing template → empty size
    }

    @Test
    @DisplayName("layerCount: fills floor..(maxBuildHeight - margin) in whole layers, never negative")
    void layerCount() {
        // Vanilla overworld: floor -64, max build height 320 (exclusive) → span 380 with the 4-row margin.
        assertEquals(76, StacksFeature.layerCount(-64, 320, 5));
        assertEquals(38, StacksFeature.layerCount(-64, 320, 10));
        assertEquals(15, StacksFeature.layerCount(-64, 320, 24));
        assertEquals(0, StacksFeature.layerCount(300, 320, 24));     // no room for even one layer
        assertEquals(0, StacksFeature.layerCount(-64, 320, 0));      // degenerate height
        // Top layer never breaches the margin.
        int h = 7, n = StacksFeature.layerCount(-64, 320, h);
        assertTrue(-64 + n * h <= 320 - StacksFeature.TOP_MARGIN);
    }
}
