package games.brennan.dungeontrain.train;

/**
 * How {@link CarriageTemplate#typeForIndex} picks a carriage variant for a
 * given index. Stored in {@code DungeonTrainConfig} (runtime-editable) and
 * paired with a per-world seed in {@code DungeonTrainWorldData} so the
 * random variants are deterministic — walking back over a stretch of track
 * always renders the same carriage at each index.
 */
public enum CarriageGenerationMode {
    /** Any of the built-in variants at each index, seeded by world seed + index. */
    RANDOM,
    /** Groups of {@code groupSize} random non-flatbed carriages, each group separated by one flatbed. */
    RANDOM_GROUPED,
    /** The original cycle: STANDARD → WINDOWED → FLATBED, repeating. */
    LOOPING
}
