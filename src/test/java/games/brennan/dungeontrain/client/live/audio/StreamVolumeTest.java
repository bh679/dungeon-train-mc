package games.brennan.dungeontrain.client.live.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamVolumeTest {

    private static float speaker(float instance, float slider) {
        return Math.max(0.0F, Math.min(1.0F, instance * slider)); // SoundEngine.calculateVolume
    }

    @Test
    void undoesTheCategorySliderExactly() {
        for (float instance : new float[]{0.1F, 0.5F, 0.8F, 1.0F}) {
            for (float slider : new float[]{0.03F, 0.25F, 0.5F, 1.0F}) {
                assertEquals(Math.min(1.0F, instance), StreamVolume.twinGain(speaker(instance, slider), slider, instance), 1e-5,
                    "instance " + instance + " slider " + slider);
            }
        }
    }

    @Test
    void loudSoundsClampLikeTheGameDoes() {
        // Thunder plays at volume 10000: clamped to 1 on the speakers whatever the slider, and on the stream too.
        assertEquals(1.0F, StreamVolume.twinGain(speaker(10000F, 0.2F), 0.2F, 10000F), 1e-6);
    }

    @Test
    void aMutedSliderStillStreamsTheSound() {
        assertEquals(0.7F, StreamVolume.twinGain(0.0F, 0.0F, 0.7F), 1e-6);
        assertEquals(1.0F, StreamVolume.twinGain(0.0F, 0.0F, 3.0F), 1e-6);
    }

    @Test
    void theGamesOwnFadesSurvive() {
        // DT fades music by scaling the category volume past the slider: speaker gain = 0.8 × slider 0.5 × fade 0.25.
        assertEquals(0.2F, StreamVolume.twinGain(0.8F * 0.5F * 0.25F, 0.5F, 0.8F), 1e-6, "the stream keeps the fade, not the slider");
    }
}
