package games.brennan.dungeontrain.client.live;

import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Which one TV plays the Live Feed's sound: only one TV has sound at a time.
 *
 * <p>A TV the viewer wears or holds that is showing an audible stream is the speaker, and every placed
 * TV is silent. Otherwise the nearest placed TV within its hearing range plays, and the rest are silent.
 * To stop two TVs at much the same distance trading the sound back and forth as the camera sways, the TV
 * already playing keeps it unless another is nearer by at least {@link #SWAP_MARGIN} blocks.</p>
 *
 * <p>Pure: {@link LiveTvSpeakers} gathers the candidates and starts or stops the sounds.</p>
 */
final class LiveSpeakerChoice {

    /** How many TVs play at once, worn or placed. */
    static final int MAX_SPEAKERS = 1;
    /** Another placed TV must be this much nearer, blocks, to take the sound from the one playing. */
    static final double SWAP_MARGIN = 0.5;

    private LiveSpeakerChoice() {}

    /**
     * The placed TV to play, or null for none.
     *
     * @param wornPlays whether the worn or held TV is playing: then no placed TV does
     * @param distances each placed TV that may play (in range, audible), by its distance from the camera
     * @param current   the placed TV playing last tick, or null
     */
    @Nullable
    static <T> T placed(boolean wornPlays, Map<T, Double> distances, @Nullable T current) {
        if (wornPlays || distances.isEmpty()) return null;
        T nearest = null;
        double nearestAt = Double.POSITIVE_INFINITY;
        for (Map.Entry<T, Double> e : distances.entrySet()) {
            if (e.getValue() < nearestAt) {
                nearest = e.getKey();
                nearestAt = e.getValue();
            }
        }
        if (current != null) {
            Double currentAt = distances.get(current);
            if (currentAt != null && currentAt <= nearestAt + SWAP_MARGIN) return current;
        }
        return nearest;
    }
}
