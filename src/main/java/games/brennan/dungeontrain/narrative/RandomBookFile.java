package games.brennan.dungeontrain.narrative;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.random.RandomGenerator;

/**
 * One standalone "random book" loaded from a {@code .json} file under
 * {@code data/<modid>/narratives/random_books/}. Unlike {@link StoryFile} —
 * which is a multi-letter narrative with per-player progression — a
 * RandomBookFile is a single book that the placeholder
 * {@code dungeontrain:random_book} item resolves to at chest-spawn time.
 *
 * <p>The {@code variants} list lets one logical book ship multiple textual
 * takes; the chest roller picks one deterministically per
 * {@code (worldSeed, carriageIndex, localPos, slot)}. {@code weight} drives
 * the in-pool weighted pick across all loaded RandomBookFiles.</p>
 *
 * <p>Immutable.</p>
 */
public record RandomBookFile(
    ResourceLocation id,
    String title,
    String author,
    int generation,
    int weight,
    List<String> variants
) {
    public RandomBookFile {
        variants = List.copyOf(variants);
        if (variants.isEmpty()) {
            throw new IllegalArgumentException(
                "RandomBookFile " + id + " has no variants");
        }
        if (weight < 0) {
            throw new IllegalArgumentException(
                "RandomBookFile " + id + " has negative weight " + weight);
        }
        // Vanilla WrittenBookContent.generation is 0..3. Clamp on the way in
        // so a malformed source can't crash the book builder downstream.
        if (generation < 0 || generation > 3) {
            generation = Math.max(0, Math.min(3, generation));
        }
    }

    /** Pick a variant index deterministically from the given seed. 0-based. */
    public int pickVariantIndex(long seed) {
        return Math.floorMod(seed, variants.size());
    }

    /** Pick a variant body deterministically from the given seed. */
    public String pickVariant(long seed) {
        return variants.get(pickVariantIndex(seed));
    }

    /** Pick a variant with an external RNG (for /command flows that don't carry a seed). */
    public String pickVariant(RandomGenerator rng) {
        return variants.get(rng.nextInt(variants.size()));
    }

    /** Path-tail of the registry id — matches the source filename without {@code .json}. */
    public String basename() {
        String path = id.getPath();
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
