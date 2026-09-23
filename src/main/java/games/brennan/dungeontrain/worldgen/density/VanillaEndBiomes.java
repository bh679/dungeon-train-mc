package games.brennan.dungeontrain.worldgen.density;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

/**
 * Vanilla's End biome layout, reimplemented so no mod can change it. {@code TheEndBiomeSource} can't be
 * trusted for this once BetterEnd: New Dawn is installed: BCLib/WorldWeaver patch the class itself, so even
 * a freshly {@code create()}d instance hands back BetterEnd biomes, and the vanilla End bands then lose
 * their chorus and End cities (both gated on {@code end_highlands}/{@code end_midlands}).
 *
 * <p>Same rule as vanilla's {@code TheEndBiomeSource#getNoiseBiome}: {@code the_end} within 64 sections of
 * the origin, otherwise the End-islands erosion at the section's centre picks highlands / midlands /
 * barrens / small islands. Erosion is {@link DensityFunctions#endIslands} seeded with the world seed,
 * which is exactly what {@code RandomState} wires into the vanilla End router.</p>
 */
public final class VanillaEndBiomes {

    /** Vanilla's main-island radius, in sections squared. */
    private static final long MAIN_ISLAND_SECTIONS_SQ = 4096L;
    static final double HIGHLANDS_ABOVE = 0.25;
    static final double MIDLANDS_FROM = -0.0625;
    static final double SMALL_ISLANDS_BELOW = -0.21875;

    private final DensityFunction erosion;
    private final Holder<Biome> end;
    private final Holder<Biome> highlands;
    private final Holder<Biome> midlands;
    private final Holder<Biome> smallIslands;
    private final Holder<Biome> barrens;

    private VanillaEndBiomes(DensityFunction erosion, HolderGetter<Biome> biomes) {
        this.erosion = erosion;
        this.end = biomes.getOrThrow(Biomes.THE_END);
        this.highlands = biomes.getOrThrow(Biomes.END_HIGHLANDS);
        this.midlands = biomes.getOrThrow(Biomes.END_MIDLANDS);
        this.smallIslands = biomes.getOrThrow(Biomes.SMALL_END_ISLANDS);
        this.barrens = biomes.getOrThrow(Biomes.END_BARRENS);
    }

    /** The vanilla End layout for a world with this seed. */
    public static VanillaEndBiomes create(long seed, HolderGetter<Biome> biomes) {
        return new VanillaEndBiomes(DensityFunctions.endIslands(seed), biomes);
    }

    /** The vanilla End biome at quart coordinates (the same arguments {@code getNoiseBiome} takes). */
    public Holder<Biome> biomeAtQuart(int quartX, int quartY, int quartZ) {
        return switch (pick(quartX, quartY, quartZ)) {
            case 0 -> end;
            case 1 -> highlands;
            case 2 -> midlands;
            case 3 -> smallIslands;
            default -> barrens;
        };
    }

    private int pick(int quartX, int quartY, int quartZ) {
        int blockX = QuartPos.toBlock(quartX);
        int blockY = QuartPos.toBlock(quartY);
        int blockZ = QuartPos.toBlock(quartZ);
        long sx = SectionPos.blockToSectionCoord(blockX);
        long sz = SectionPos.blockToSectionCoord(blockZ);
        if (sx * sx + sz * sz <= MAIN_ISLAND_SECTIONS_SQ) return 0;
        int cx = (SectionPos.blockToSectionCoord(blockX) * 2 + 1) * 8;
        int cz = (SectionPos.blockToSectionCoord(blockZ) * 2 + 1) * 8;
        return classify(erosion.compute(new DensityFunction.SinglePointContext(cx, blockY, cz)));
    }

    /** Vanilla's erosion thresholds: 1 highlands, 2 midlands, 3 small islands, 4 barrens. */
    static int classify(double erosion) {
        if (erosion > HIGHLANDS_ABOVE) return 1;
        if (erosion >= MIDLANDS_FROM) return 2;
        return erosion < SMALL_ISLANDS_BELOW ? 3 : 4;
    }
}
