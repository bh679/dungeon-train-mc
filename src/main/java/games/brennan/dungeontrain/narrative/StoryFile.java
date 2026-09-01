package games.brennan.dungeontrain.narrative;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/**
 * Parsed narrative story loaded from a {@code .json} file under
 * {@code data/<modid>/narratives/stories/}. Each file is one story containing
 * one or more {@link Letter}s; the runtime renders each Letter as a single
 * Minecraft signed book.
 *
 * <p>{@code weight} drives which uncompleted story a lectern serves next, and each story's share
 * of the post-completion re-read pool. {@code 1.0} is the baseline; a lower value defers a series
 * rather than removing it — a player who reads everything still reaches it, just later.</p>
 *
 * <p>Immutable. The {@code letters} list preserves source order.</p>
 */
public record StoryFile(
    ResourceLocation id,
    String character,
    String story,
    double weight,
    List<Letter> letters
) {
    public StoryFile {
        letters = List.copyOf(letters);
        if (!Double.isFinite(weight) || weight < 0) {
            throw new IllegalArgumentException(
                "StoryFile " + id + " has invalid weight " + weight);
        }
    }

    /**
     * A copy of this story carrying {@code newWeight}. Used by {@link StoryRegistry} to keep the
     * ENGLISH base file's weight when a localized copy has replaced the prose — weight is tuning,
     * not translated content.
     */
    public StoryFile withWeight(double newWeight) {
        return new StoryFile(id, character, story, newWeight, letters);
    }

    /** Find a Letter by its 1-based index. */
    public Optional<Letter> letterByIndex(int index) {
        for (Letter l : letters) {
            if (l.index() == index) return Optional.of(l);
        }
        return Optional.empty();
    }
}
