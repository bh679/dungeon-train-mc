package games.brennan.dungeontrain.train;

/**
 * How {@link CarriageTemplate#typeForIndex} picks a carriage variant for a
 * given index. Stored in {@code DungeonTrainConfig} (runtime-editable) and
 * paired with a per-world seed in {@code DungeonTrainWorldData} so the
 * random variants are deterministic — walking back over a stretch of track
 * always renders the same carriage at each index.
 */
public enum CarriageGenerationMode {
    /** Any of the four variants at each index, seeded by world seed + index. */
    RANDOM,
    /** Groups of {@code groupSize} random non-flatbed carriages, each group separated by one flatbed. */
    RANDOM_GROUPED,
    /** The original 4-way cycle: STANDARD → WINDOWED → SOLID_ROOF → FLATBED, repeating. */
    LOOPING
}
