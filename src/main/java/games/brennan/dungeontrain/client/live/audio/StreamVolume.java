package games.brennan.dungeontrain.client.live.audio;

/**
 * The stream hears the game at full volume, whatever the streamer's sliders say. Minecraft bakes a
 * sound's category slider into its source gain ({@code clamp(instanceVolume × slider)}) and applies
 * the master slider as the listener gain; the mirror keeps its listener at 1 and undoes the category
 * slider per source here. Pure — unit-tested on a bare JVM.
 */
public final class StreamVolume {

    private StreamVolume() {}

    /**
     * The twin's gain for a speaker source set to {@code speakerGain} under category slider
     * {@code slider}. Dividing undoes the slider exactly — both sides are clamped to [0, 1] the same
     * way — and keeps whatever the game itself did to the volume (DT's music fades scale it beyond
     * the slider). With the slider at 0 the speaker gain carries nothing, so the sound's own volume is used.
     */
    public static float twinGain(float speakerGain, float slider, float instanceVolume) {
        if (slider > 0.0F) return clamp(speakerGain / slider);
        return clamp(instanceVolume);
    }

    private static float clamp(float v) {
        return Float.isNaN(v) ? 0.0F : Math.max(0.0F, Math.min(1.0F, v));
    }
}
