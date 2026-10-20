package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Biome lookups for one End-band sample, answered once per column.
 *
 * <p>Generating a sample asks its biome source well over a thousand times a chunk: {@code
 * fillBiomesFromNoise} fills 16 × 16 quarts for every one of the 16 sections, and the surface rules'
 * fuzzed lookups reach into the quarts just outside it. End biome sources do not vary with height
 * (vanilla's by design; WorldWeaver's hex map reads only X and Z), so the same {@code (qx, qz)} is asked
 * 64 times for one answer — 13% of a BetterEnd sample's CPU on 2026-10-20, 12 µs a lookup.</p>
 *
 * <p>{@link #memoised} wraps the source in a per-sample memo keyed by column, <b>only after
 * {@link #isFlat} has checked the source</b>: a few dozen random quarts around the sample are read at
 * several heights and compared. The verdict is kept per source instance for the server's life, so a
 * Y-dependent source (a future WorldWeaver, a different End mod) is detected once, logged once, and
 * sampled plainly. The memo lives for one sample on one thread; its possible biomes are the source's own,
 * in the source's order, so feature indexes and decoration seeds are unchanged.</p>
 *
 * <p>The memo logic itself is generic ({@link ColumnMemo}) and unit-tested without a registry.</p>
 */
public final class EndBandBiomeFill {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Random quarts read per flatness probe, and the quart-Y rows each is compared across. */
    static final int PROBE_COLUMNS = 48;
    static final int[] PROBE_QUART_YS = {0, 8, 16, 24, 31};
    /** Half-side of the probe's XZ window around the sample, in quarts (256 quarts = 1024 blocks). */
    static final int PROBE_RADIUS_QUARTS = 256;

    /** Flatness verdict per source instance: true = the memo may be used. */
    private static final Map<BiomeSource, Boolean> VERDICTS = new ConcurrentHashMap<>();

    private EndBandBiomeFill() {}

    /** A biome answered by column and height — the seam the generic memo and probe work through. */
    @FunctionalInterface
    public interface Lookup<T> {
        T at(int quartX, int quartY, int quartZ);
    }

    /**
     * True when {@code source} gives the same answer at every probed height for every probed column.
     * Columns are drawn pseudo-randomly (from {@code seed}) within {@link #PROBE_RADIUS_QUARTS} of
     * {@code (centreQx, centreQz)}. Pure in its arguments.
     */
    static <T> boolean isFlat(Lookup<T> source, long seed, int centreQx, int centreQz) {
        Random random = new Random(seed);
        for (int i = 0; i < PROBE_COLUMNS; i++) {
            int qx = centreQx + random.nextInt(2 * PROBE_RADIUS_QUARTS + 1) - PROBE_RADIUS_QUARTS;
            int qz = centreQz + random.nextInt(2 * PROBE_RADIUS_QUARTS + 1) - PROBE_RADIUS_QUARTS;
            T first = source.at(qx, PROBE_QUART_YS[0], qz);
            for (int r = 1; r < PROBE_QUART_YS.length; r++) {
                if (!Objects.equals(first, source.at(qx, PROBE_QUART_YS[r], qz))) return false;
            }
        }
        return true;
    }

    /** {@code source} remembered per column: the first height asked answers for every height after. Single-threaded. */
    public static final class ColumnMemo<T> implements Lookup<T> {

        private final Lookup<T> source;
        private final Map<Long, T> memo = new HashMap<>(512);
        private int misses;

        public ColumnMemo(Lookup<T> source) {
            this.source = Objects.requireNonNull(source);
        }

        @Override
        public T at(int quartX, int quartY, int quartZ) {
            long key = ChunkPos.asLong(quartX, quartZ);
            T value = memo.get(key);
            if (value == null) {
                value = source.at(quartX, quartY, quartZ);
                memo.put(key, value);
                misses++;
            }
            return value;
        }

        /** How many columns were asked of the source — the lookups the memo could not save. */
        public int misses() {
            return misses;
        }
    }

    /**
     * {@code source} for one sample: memoised by column when the source has been found Y-flat
     * ({@link #isFlat}, probed around {@code near} on the first call for this source), else the source itself.
     * The returned source is used by one sampler thread for one sample and then dropped.
     */
    public static BiomeSource memoised(BiomeSource source, Climate.Sampler sampler, long seed, ChunkPos near) {
        Boolean flat = VERDICTS.get(source);
        if (flat == null) {
            boolean verdict = isFlat((qx, qy, qz) -> source.getNoiseBiome(qx, qy, qz, sampler), seed,
                    QuartPos.fromBlock(near.getMinBlockX()), QuartPos.fromBlock(near.getMinBlockZ()));
            if (VERDICTS.putIfAbsent(source, verdict) == null) {
                if (verdict) {
                    LOGGER.info("[DungeonTrain] End-band biome source {} is Y-flat; samples read each column once",
                            source.getClass().getSimpleName());
                } else {
                    LOGGER.warn("[DungeonTrain] End-band biome source {} varies with height; samples read every quart",
                            source.getClass().getSimpleName());
                }
            }
            flat = verdict;
        }
        return flat ? new Memoised(source, sampler) : source;
    }

    /** Forget every verdict (server stopped). */
    public static void clear() {
        VERDICTS.clear();
    }

    /** A sample-only source over a column memo; never registered or saved. */
    private static final class Memoised extends BiomeSource {
        private final MapCodec<Memoised> codec = MapCodec.unit(() -> {
            throw new UnsupportedOperationException("End-band memoised biome source is sample-only and never serialised");
        });
        private final BiomeSource live;
        private final ColumnMemo<Holder<Biome>> memo;

        private Memoised(BiomeSource live, Climate.Sampler sampler) {
            this.live = live;
            this.memo = new ColumnMemo<>((qx, qy, qz) -> live.getNoiseBiome(qx, qy, qz, sampler));
        }

        @Override
        protected MapCodec<? extends BiomeSource> codec() {
            return codec;
        }

        @Override
        protected Stream<Holder<Biome>> collectPossibleBiomes() {
            return live.possibleBiomes().stream();      // the live set in the live order: feature indexes unchanged
        }

        @Override
        public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
            return memo.at(quartX, quartY, quartZ);     // the sample's sampler is the one it was built with
        }
    }
}
