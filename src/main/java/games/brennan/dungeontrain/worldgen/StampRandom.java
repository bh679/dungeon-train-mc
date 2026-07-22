package games.brennan.dungeontrain.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

/**
 * Position-pure {@link RandomSource} for worldgen template stamps.
 *
 * <p>In 1.21.1 {@code StructureTemplate.placeInWorld} consumes its random parameter for exactly
 * one thing: {@code LootTableSeed} on stamped {@code RandomizableContainer} block entities
 * (palette selection and every shipped processor are position-seeded separately). Passing the
 * shared, nanotime-seeded level random therefore made container loot the last per-run-varying
 * output of the otherwise deterministic track/tunnel stamps. Deriving the source from
 * (worldSeed, stamp origin) instead makes twin same-seed runs roll identical loot.
 */
public final class StampRandom {

    private StampRandom() {}

    /** A fresh random seeded purely from the world seed and the stamp origin position. */
    public static RandomSource at(long worldSeed, BlockPos origin) {
        // Splitmix64 finalizer over seed ⊕ position, same constants as
        // DungeonTrainWorldData.deriveGenerationSeed — decorrelated from vanilla streams.
        long h = worldSeed ^ (origin.asLong() * 0x9E3779B97F4A7C15L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return RandomSource.create(h ^ (h >>> 31));
    }
}
