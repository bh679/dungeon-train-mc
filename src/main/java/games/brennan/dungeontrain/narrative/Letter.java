package games.brennan.dungeontrain.narrative;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * One chapter of a {@link StoryFile}. A Letter has at least one variant; if the
 * source had {@code Alt} blocks, each one becomes an additional variant. The
 * runtime picks one variant per book spawn — variants are content-equivalent
 * (e.g. multiple takes of the same letter from different angles).
 *
 * <p>{@code index} is 1-based and stable across reloads (preserves source
 * order). {@code label} is the original "Letter One" / "Letter 3" label from
 * the source — used for display when the story has no title of its own.</p>
 */
public record Letter(
    int index,
    String label,
    List<String> variants
) {
    public Letter {
        variants = List.copyOf(variants);
        if (variants.isEmpty()) {
            throw new IllegalArgumentException("Letter " + index + " '" + label + "' has no variants");
        }
    }

    /** Pick a variant deterministically from the given seed. */
    public String pickVariant(long seed) {
        int idx = Math.floorMod(seed, variants.size());
        return variants.get(idx);
    }

    /** Pick a variant with an external RNG (for /command flows that don't carry a seed). */
    public String pickVariant(RandomGenerator rng) {
        return variants.get(rng.nextInt(variants.size()));
    }
}
