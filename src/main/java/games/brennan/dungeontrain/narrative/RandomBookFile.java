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
 * the in-pool weighted pick across all loaded RandomBookFiles — fractional, so a deliberately
 * rare book (a donation or meta ask) can sit below the {@code 1.0} baseline without being
 * switched off entirely.</p>
 *
 * <p>Immutable.</p>
 */
public record RandomBookFile(
    ResourceLocation id,
    String title,
    String author,
    int generation,
    double weight,
    List<String> variants
) {
    public RandomBookFile {
        variants = List.copyOf(variants);
        if (variants.isEmpty()) {
            throw new IllegalArgumentException(
                "RandomBookFile " + id + " has no variants");
        }
        if (!Double.isFinite(weight) || weight < 0) {
            throw new IllegalArgumentException(
                "RandomBookFile " + id + " has invalid weight " + weight);
        }
        // Vanilla WrittenBookContent.generation is 0..3. Clamp on the way in
        // so a malformed source can't crash the book builder downstream.
        if (generation < 0 || generation > 3) {
            generation = Math.max(0, Math.min(3, generation));
        }
    }

    /**
     * A copy of this book carrying {@code newWeight}. Used by the registries to keep the ENGLISH
     * base file's weight when a localized copy has replaced the prose — weight is tuning, not
     * translated content.
     */
    public RandomBookFile withWeight(double newWeight) {
        return new RandomBookFile(id, title, author, generation, newWeight, variants);
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
