package games.brennan.dungeontrain.worldgen;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
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

    /**
     * The same altitude zones in Biomes O' Plenty's look, for mountain stages that border its stretch
     * (see {@code SecondLapOverworld.lookAt}). Keys missing from the registry are dropped; an empty zone
     * (the peaks — BoP has no bare-peak biome) uses the {@link #ZONES} zone instead.
     */
    public static final List<List<ResourceKey<Biome>>> BOP_ZONES = List.of(
            List.of(bop("seasonal_forest"), bop("maple_woods"), bop("woodland"), bop("aspen_glade"), bop("redwood_forest")),
            List.of(bop("coniferous_forest"), bop("highland"), bop("fir_clearing")),
            List.of(bop("snowy_coniferous_forest"), bop("snowy_fir_clearing"), bop("snowy_maple_woods")),
            List.of());

    /** Cave biomes for the mountain interior on the way <em>into</em> the Nether core. */
    public static final List<ResourceKey<Biome>> CAVE_PRE = List.of(Biomes.LUSH_CAVES, Biomes.DRIPSTONE_CAVES);
    /** Cave biomes for the mountain interior on the way <em>out</em> of the core — deep dark joins. */
    public static final List<ResourceKey<Biome>> CAVE_POST =
            List.of(Biomes.LUSH_CAVES, Biomes.DRIPSTONE_CAVES, Biomes.DEEP_DARK);
    /** Cave-biome regions are 128-block cells (a cave biome holds over a whole cavern, not a corner of it). */
    public static final int CAVE_REGION_SHIFT = 7;
    private static final long CAVE_SALT = 0x7F4A7C159E3779B9L;

    /** Deterministic cave-biome choice for a column — {@link #pickWithinZone}'s hash over 128-block regions. */
    public static int pickCave(long seed, int worldX, int worldZ, int size) {
        return pickWithinRegion(seed ^ CAVE_SALT, worldX, worldZ, size, CAVE_REGION_SHIFT);
    }

    /** Deep-dark areas after the core are drawn over 512-block regions (bigger than the lush/dripstone mix). */
    public static final int DEEP_DARK_REGION_SHIFT = 9;
    /** Of every {@link #DEEP_DARK_WEIGHT_OF} coarse regions after the core, this many are deep dark. */
    public static final int DEEP_DARK_WEIGHT = 3;
    public static final int DEEP_DARK_WEIGHT_OF = 4;
    private static final long DEEP_DARK_SALT = 0x1B873593CC9E2D51L;

    /**
     * Index into {@link #CAVE_POST} for a fall-side column: three quarters of the 512-block regions are
     * deep dark (the palette's last entry); the rest fall through to the 128-block lush / dripstone mix, so
     * those two blend while the deep dark stays large and mostly itself.
     */
    public static int pickCavePost(long seed, int worldX, int worldZ) {
        int coarse = pickWithinRegion(seed ^ DEEP_DARK_SALT, worldX, worldZ, DEEP_DARK_WEIGHT_OF, DEEP_DARK_REGION_SHIFT);
        if (coarse < DEEP_DARK_WEIGHT) return CAVE_POST.size() - 1;
        return pickCave(seed, worldX, worldZ, CAVE_POST.size() - 1);
    }

    private static ResourceKey<Biome> bop(String path) {
        return ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("biomesoplenty", path));
    }

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
        return pickWithinRegion(seed, worldX, worldZ, size, 6);
    }

    /** {@link #pickWithinZone} over {@code 2^regionShift}-block regions. */
    public static int pickWithinRegion(long seed, int worldX, int worldZ, int size, int regionShift) {
        if (size <= 1) return 0;
        int rx = worldX >> regionShift;
        int rz = worldZ >> regionShift;
        long h = seed * 0x9E3779B97F4A7C15L;
        h ^= (long) rx * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        h ^= (long) rz * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return (int) Math.floorMod(h, (long) size);
    }
}
