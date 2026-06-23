package games.brennan.dungeontrain.worldgen;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import java.util.List;

/**
 * The curated <b>highland biome palette</b> forced onto nether-band mountain columns so vanilla
 * places trees, flowers, snow caps, and biome-appropriate structures on the (noise-raised) real
 * terrain — fixing the "bare mountain" symptom where the band inherited tree-less lowland/ocean
 * biomes from the original un-raised climate.
 *
 * <p>Biomes are chosen by <b>altitude</b> (world-Y) so a tall mountain's flanks pass forest →
 * spruce → snow → bare peak, and short stages stay forested. Within a zone the pick varies by a
 * coarse {@code 64}-block region hash of the per-world seed, so neighbouring regions differ but a
 * biome holds coherently over an area (not per-block noise).</p>
 *
 * <p>The zone math + region pick are pure (no registry) so they're unit-testable; the
 * {@link ResourceKey} lists are resolved to {@code Holder<Biome>} at server start by
 * {@link games.brennan.dungeontrain.worldgen.density.NetherBandBiomeSet}.</p>
 */
public final class NetherBandBiomes {

    private NetherBandBiomes() {}

    /** Zone boundaries (world-Y). Base &lt; MID ≤ mid &lt; HIGH ≤ high &lt; PEAK ≤ peak. */
    public static final int MID_Y = 100;
    public static final int HIGH_Y = 145;
    public static final int PEAK_Y = 175;

    /** Biome keys per altitude zone: [0]=base, [1]=mid, [2]=high, [3]=peak. */
    public static final List<List<ResourceKey<Biome>>> ZONES = List.of(
            List.of(Biomes.MEADOW, Biomes.FOREST, Biomes.FLOWER_FOREST, Biomes.TAIGA),
            List.of(Biomes.GROVE, Biomes.OLD_GROWTH_PINE_TAIGA, Biomes.WINDSWEPT_FOREST),
            List.of(Biomes.SNOWY_SLOPES),
            List.of(Biomes.JAGGED_PEAKS, Biomes.FROZEN_PEAKS, Biomes.STONY_PEAKS));

    /** Altitude zone index (0 base → 3 peak) for a world-Y. */
    public static int zoneIndex(int worldY) {
        if (worldY < MID_Y) return 0;
        if (worldY < HIGH_Y) return 1;
        if (worldY < PEAK_Y) return 2;
        return 3;
    }

    /**
     * Deterministic biome choice within a zone of {@code size} options — a coarse 64-block region
     * hash so a single biome holds over an area rather than flickering per column. Always in
     * {@code [0, size)} (returns 0 for {@code size ≤ 1}).
     */
    public static int pickWithinZone(long seed, int worldX, int worldZ, int size) {
        if (size <= 1) return 0;
        int rx = worldX >> 6;
        int rz = worldZ >> 6;
        long h = seed * 0x9E3779B97F4A7C15L;
        h ^= (long) rx * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        h ^= (long) rz * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return (int) Math.floorMod(h, (long) size);
    }
}
