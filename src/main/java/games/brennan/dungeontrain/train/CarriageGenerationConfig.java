package games.brennan.dungeontrain.train;

/**
 * Immutable tuple passed to {@link CarriageTemplate#typeForIndex} describing
 * how to pick a carriage for index {@code i}. {@code mode} + {@code groupSize}
 * live in {@code DungeonTrainConfig} (runtime-editable); {@code seed} lives in
 * {@code DungeonTrainWorldData} and is fixed per-world.
 *
 * <p>The compact constructor clamps {@code groupSize} into
 * {@code [MIN_GROUP_SIZE, MAX_GROUP_SIZE]} so stale TOML / UI input can't
 * produce a degenerate zero-size group (which would put a flatbed at every
 * index) or a multi-hundred-size group (which would push flatbeds outside any
 * realistic rolling-window).</p>
 *
 * <p>Mirrors {@link CarriageDims}' record-with-clamp idiom.</p>
 */
public record CarriageGenerationConfig(CarriageGenerationMode mode, int groupSize, long seed) {

    public static final int MIN_GROUP_SIZE = 1;
    public static final int MAX_GROUP_SIZE = 16;
    public static final int DEFAULT_GROUP_SIZE = 3;

    /** Shipped default — Random Grouped with groups of 3. Seed is 0 (swap in the real world seed at call sites). */
    public static final CarriageGenerationConfig DEFAULT =
            new CarriageGenerationConfig(CarriageGenerationMode.RANDOM_GROUPED, DEFAULT_GROUP_SIZE, 0L);

    /** Preserves the pre-feature 4-way cycle. Used by tests that want to pin legacy behaviour. */
    public static final CarriageGenerationConfig LOOPING =
            new CarriageGenerationConfig(CarriageGenerationMode.LOOPING, DEFAULT_GROUP_SIZE, 0L);

    public CarriageGenerationConfig {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        groupSize = Math.max(MIN_GROUP_SIZE, Math.min(MAX_GROUP_SIZE, groupSize));
    }

    /**
     * Forgiving factory mirroring {@link CarriageDims#clamp} — accepts any
     * inputs and produces a valid config.
     */
    public static CarriageGenerationConfig clamp(CarriageGenerationMode mode, int groupSize, long seed) {
        CarriageGenerationMode m = mode == null ? CarriageGenerationMode.RANDOM_GROUPED : mode;
        return new CarriageGenerationConfig(m, groupSize, seed);
    }
}
