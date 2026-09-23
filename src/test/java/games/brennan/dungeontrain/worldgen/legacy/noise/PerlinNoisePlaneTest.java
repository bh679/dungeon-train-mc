package games.brennan.dungeontrain.worldgen.legacy.noise;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The z = 0 fast path must agree with the full 3-D sample it replaces. */
final class PerlinNoisePlaneTest {

    @Test
    @DisplayName("samplePlane == sample(x, y, 0) for the Classic/Indev (no-offset) form")
    void planeMatchesFullSample() {
        PerlinNoise noise = new PerlinNoise(new Random(42L), false);
        Random pts = new Random(7L);
        for (int i = 0; i < 20_000; i++) {
            double x = (pts.nextDouble() - 0.5) * 4096.0;
            double y = (pts.nextDouble() - 0.5) * 4096.0;
            assertEquals(noise.sample(x, y, 0.0), noise.samplePlane(x, y), 0.0);
        }
    }

    @Test
    @DisplayName("with an origin offset the plane sampler falls back to the full sample")
    void offsetFallsBack() {
        PerlinNoise noise = new PerlinNoise(new Random(42L));
        assertEquals(noise.sample(12.3, -4.5, 0.0), noise.samplePlane(12.3, -4.5), 0.0);
    }
}
