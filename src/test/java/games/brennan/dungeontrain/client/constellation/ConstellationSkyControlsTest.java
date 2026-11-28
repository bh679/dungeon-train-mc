package games.brennan.dungeontrain.client.constellation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConstellationSkyControlsTest {

    @Test
    void barFadesInAndOutOverFadeMs() {
        assertEquals(1.0, ConstellationSkyControls.step(0.0, true, ConstellationSkyControls.FADE_MS), 1e-9);
        assertEquals(0.5, ConstellationSkyControls.step(0.0, true, ConstellationSkyControls.FADE_MS / 2), 1e-2);
        assertEquals(0.0, ConstellationSkyControls.step(1.0, false, ConstellationSkyControls.FADE_MS), 1e-9);
        assertEquals(1.0, ConstellationSkyControls.step(1.0, true, 1000), 1e-9);
    }

    @Test
    void glowColourCarriesAlpha() {
        assertEquals(0, ConstellationWindowGlow.colour(0f) >>> 24);
        assertEquals(255, ConstellationWindowGlow.colour(1f) >>> 24);
    }

    @Test
    void glowSlicesThinOutUpward() {
        float low = ConstellationWindowGlow.sliceAlpha(0.05f), high = ConstellationWindowGlow.sliceAlpha(0.95f);
        org.junit.jupiter.api.Assertions.assertTrue(low > high);
        assertEquals(0f, ConstellationWindowGlow.sliceAlpha(1f), 1e-6f);
    }
}
