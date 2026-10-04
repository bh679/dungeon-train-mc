package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.config.EndBandConfig;
import games.brennan.dungeontrain.worldgen.density.OverworldStretchBiomes;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The BetterEnd End band without its vanilla patches (#1785).
 *
 * <p>WorldWeaver's End map ({@code wover:end_biome_source}) keeps vanilla's End biomes alongside
 * BetterEnd's — roughly two fifths of it. Vanilla {@code end_barrens} and {@code small_end_islands}
 * place no features at all, so the band copied them faithfully as flat bare end stone beside lush
 * BetterEnd chunks. This remaps those vanilla biomes to BetterEnd ones <b>for the band's samples
 * only</b>: {@link EndBandSampler} fills a sample's biomes, surface and features through
 * {@link #wrap}, and {@code EndCoreBiomes} resolves the display label through the same instance, so
 * label, surface skin and decoration agree. The real End dimension is untouched.</p>
 *
 * <p>Vanilla land biomes ({@code the_end}, {@code end_highlands}, {@code end_midlands},
 * {@code end_barrens}) become a BetterEnd land biome ({@code wover:is_end/land}); the void biome
 * {@code small_end_islands} becomes a BetterEnd small-island biome ({@code wover:is_end/small_island}).
 * Which one is a pure function of the world seed and the End quart ({@link #pickIndex}): jittered
 * Voronoi cells of {@value #CELL_QUARTS} quarts, so the replacements come in coherent clumps with
 * irregular borders rather than per-quart noise. Tables are sorted by biome id, so no per-boot
 * registry order reaches the world. With no BetterEnd biomes to draw from (the mod absent, its tags
 * renamed) the remap is the identity.</p>
 *
 * <p>Built once per End seed and shared by the inline and background samplers, so the cached ground
 * ({@link EndBandGroundCache}) is identical whichever generated it; the config switch is read once
 * here for the same reason. Cleared with the rest of the End-band state when the server stops.</p>
 */
public final class EndBandBiomeRemap {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Side of a Voronoi cell in End quarts (64 quarts = 256 blocks, about a BetterEnd biome). */
    static final int CELL_QUARTS = 64;
    private static final long SALT = 0x1785_E4DB_10BE_5EEDL;

    private static final TagKey<Biome> LAND = TagKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath("wover", "is_end/land"));
    private static final TagKey<Biome> SMALL_ISLAND = TagKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath("wover", "is_end/small_island"));
    private static final List<ResourceKey<Biome>> VANILLA_LAND =
            List.of(Biomes.THE_END, Biomes.END_HIGHLANDS, Biomes.END_MIDLANDS, Biomes.END_BARRENS);

    /**
     * The replacement pools, generic so the picking logic is testable without a registry:
     * {@code land} for vanilla land biomes, {@code voids} for {@code small_end_islands}. Either may be
     * empty, in which case that class is left as it is.
     */
    public record Table<T>(List<T> land, List<T> voids) {

        /** Pools drawn from {@code all}, each sorted by {@code id} so input order never matters. */
        public static <T> Table<T> of(Collection<T> all, Function<T, String> id,
                                      Predicate<T> isLand, Predicate<T> isVoid) {
            Comparator<T> byId = Comparator.comparing(id);
            return new Table<>(
                    all.stream().filter(isLand).sorted(byId).toList(),
                    all.stream().filter(isVoid).sorted(byId).toList());
        }

        public boolean isEmpty() {
            return land.isEmpty() && voids.isEmpty();
        }
    }

    private static volatile EndBandBiomeRemap built;

    private final long seed;
    private final boolean enabled;
    private final Table<Holder<Biome>> table;

    private EndBandBiomeRemap(long seed, boolean enabled, Table<Holder<Biome>> table) {
        this.seed = seed;
        this.enabled = enabled;
        this.table = table;
    }

    /** The remap for this server's End; built on first use per End seed. Never {@code null}. */
    public static EndBandBiomeRemap forEnd(ServerLevel end) {
        long seed = end.getSeed();
        EndBandBiomeRemap r = built;
        if (r != null && r.seed == seed) return r;
        synchronized (EndBandBiomeRemap.class) {
            r = built;
            if (r != null && r.seed == seed) return r;
            r = build(end, seed);
            built = r;
            return r;
        }
    }

    /** Drop the built remap (server stopped). */
    public static void clear() {
        built = null;
    }

    private static EndBandBiomeRemap build(ServerLevel end, long seed) {
        boolean enabled = EndBandConfig.betterEndOnly();
        Table<Holder<Biome>> table = new Table<>(List.of(), List.of());
        if (enabled) {
            try {
                Registry<Biome> biomes = end.registryAccess().registryOrThrow(Registries.BIOME);
                List<Holder<Biome>> all = new ArrayList<>();
                biomes.getTag(LAND).ifPresent(named -> named.forEach(all::add));
                biomes.getTag(SMALL_ISLAND).ifPresent(named -> named.forEach(all::add));
                table = Table.of(all, EndBandBiomeRemap::id,
                        h -> h.is(LAND) && !OverworldStretchBiomes.isBop(h),
                        h -> h.is(SMALL_ISLAND) && !OverworldStretchBiomes.isBop(h));
            } catch (Throwable t) {
                LOGGER.warn("[DungeonTrain] End-band biome remap unavailable; vanilla End patches stay vanilla", t);
            }
            if (table.isEmpty()) {
                LOGGER.warn("[DungeonTrain] No BetterEnd biomes tagged {} / {} — End-band vanilla patches stay vanilla",
                        LAND.location(), SMALL_ISLAND.location());
            } else {
                LOGGER.info("[DungeonTrain] End-band vanilla biomes remap to {} BetterEnd land + {} small-island biomes",
                        table.land().size(), table.voids().size());
            }
        }
        return new EndBandBiomeRemap(seed, enabled, table);
    }

    private static String id(Holder<Biome> h) {
        return h.unwrapKey().map(k -> k.location().toString()).orElse("");
    }

    /** True when this remap changes anything at all. */
    public boolean isActive() {
        return enabled && !table.isEmpty();
    }

    /**
     * {@code live} as the band shows it: itself unless it is a vanilla End biome, else the BetterEnd
     * biome of the same class (land / small island) picked for End quart {@code (qx, qz)}.
     */
    public Holder<Biome> apply(Holder<Biome> live, int qx, int qz) {
        if (!isActive() || live == null) return live;
        if (live.is(Biomes.SMALL_END_ISLANDS)) return pick(table.voids(), live, qx, qz);
        for (ResourceKey<Biome> key : VANILLA_LAND) {
            if (live.is(key)) return pick(table.land(), live, qx, qz);
        }
        return live;
    }

    private Holder<Biome> pick(List<Holder<Biome>> pool, Holder<Biome> fallback, int qx, int qz) {
        if (pool.isEmpty()) return fallback;
        return pool.get(pickIndex(seed, qx, qz, pool.size()));
    }

    /** {@code live} seen through {@link #apply}; its possible biomes are {@code live}'s own, in the same order. */
    public BiomeSource wrap(BiomeSource live) {
        return isActive() ? new Remapped(live) : live;
    }

    /**
     * Which of {@code n} pool entries End quart {@code (qx, qz)} gets: the entry of the nearest of the
     * jittered cell centres around it (Voronoi over {@value #CELL_QUARTS}-quart cells). Pure in its
     * arguments; Y plays no part because End biomes do not vary with height.
     */
    static int pickIndex(long seed, int qx, int qz, int n) {
        if (n <= 1) return 0;
        int cellX = Math.floorDiv(qx, CELL_QUARTS);
        int cellZ = Math.floorDiv(qz, CELL_QUARTS);
        long bestDist = Long.MAX_VALUE;
        long bestHash = 0L;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = cellX + dx;
                int cz = cellZ + dz;
                long h = mix(seed ^ SALT, cx, cz);
                long px = (long) cx * CELL_QUARTS + (h & (CELL_QUARTS - 1));
                long pz = (long) cz * CELL_QUARTS + ((h >>> 8) & (CELL_QUARTS - 1));
                long ddx = px - qx;
                long ddz = pz - qz;
                long dist = ddx * ddx + ddz * ddz;
                if (dist < bestDist || (dist == bestDist && Long.compareUnsigned(h, bestHash) < 0)) {
                    bestDist = dist;
                    bestHash = h;
                }
            }
        }
        return (int) Math.floorMod(bestHash >>> 16, (long) n);
    }

    /** SplitMix64 over the seed and a cell coordinate pair. */
    private static long mix(long seed, int x, int z) {
        long v = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        v += 0x9E3779B97F4A7C15L;
        v = (v ^ (v >>> 30)) * 0xBF58476D1CE4E5B9L;
        v = (v ^ (v >>> 27)) * 0x94D049BB133111EBL;
        return v ^ (v >>> 31);
    }

    /** A sample-only {@link BiomeSource}: never registered or saved, so its codec refuses to be used. */
    private final class Remapped extends BiomeSource {
        private final MapCodec<Remapped> codec = MapCodec.unit(() -> {
            throw new UnsupportedOperationException("End-band remapped biome source is sample-only and never serialised");
        });
        private final BiomeSource live;

        private Remapped(BiomeSource live) {
            this.live = live;
        }

        @Override
        protected MapCodec<? extends BiomeSource> codec() {
            return codec;
        }

        @Override
        protected Stream<Holder<Biome>> collectPossibleBiomes() {
            // The live set, in the live order: the targets are already in it, so the generator's feature
            // indexes (and so every decoration seed) are exactly the live End's.
            return live.possibleBiomes().stream();
        }

        @Override
        public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
            return apply(live.getNoiseBiome(quartX, quartY, quartZ, sampler), quartX, quartZ);
        }
    }
}
