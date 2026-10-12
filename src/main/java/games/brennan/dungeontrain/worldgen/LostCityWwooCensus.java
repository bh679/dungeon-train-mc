package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Picks, once per world, which Lost City buildings may start in the WWOO stretch
 * ({@link LostCityStructures#wwooBuildings}): half of them, or fewer when the stretch will show fewer
 * cities than that. The city count is a census of Lap 1's whole WWOO band within {@link #TRACK_CHUNKS} of
 * the track: one start per Lost City structure set whose placement grid lands on a chunk the WWOO roll
 * keeps ({@link LostCityStructures#allowedAt(long, WorldGenCycle, int, int)}). Placement maths only —
 * nothing is generated — and an upper bound, since a start can still fail on its biome.
 *
 * <p>Both DT's {@code lost_city} set and Big Lost City's own per-structure sets are counted: all are live
 * placement grids. The candidate buildings come from the structure registry, so buildings added later (DT's
 * variations, a datapack) join the pool without a code change.</p>
 */
public final class LostCityWwooCensus {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Chunks either side of the track (which runs along Z≈0) that count as "near the track". */
    public static final int TRACK_CHUNKS = 10;

    private record Cached(ChunkGeneratorStructureState state, long seed, WorldGenCycle cycle, Set<String> buildings) {}

    private static volatile Cached cached;

    private LostCityWwooCensus() {}

    /** This world's WWOO buildings, computed on first use and cached for its structure state, seed and cycle. */
    public static Set<String> buildings(ServerLevel level, long seed, WorldGenCycle cycle) {
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        Cached c = cached;
        if (c != null && c.state() == state && c.seed() == seed && c.cycle() == cycle) return c.buildings();
        synchronized (LostCityWwooCensus.class) {
            c = cached;
            if (c != null && c.state() == state && c.seed() == seed && c.cycle() == cycle) return c.buildings();
            Set<String> picked = compute(level, state, seed, cycle);
            cached = new Cached(state, seed, cycle, picked);
            return picked;
        }
    }

    private static Set<String> compute(ServerLevel level, ChunkGeneratorStructureState state, long seed, WorldGenCycle cycle) {
        long t0 = System.nanoTime();
        try {
            List<ResourceLocation> ids = level.registryAccess().registryOrThrow(Registries.STRUCTURE).keySet().stream()
                    .filter(LostCityStructures::isLostCityStructure)
                    .filter(id -> !LostCityStructures.isNewBuildingSlot(id))
                    .toList();
            int starts = expectedStarts(state, seed, cycle);
            Set<String> picked = LostCityStructures.wwooBuildings(seed, ids, starts);
            Set<String> all = new TreeSet<>();
            ids.forEach(id -> all.add(LostCityStructures.building(id)));
            LOGGER.info("[DungeonTrain] WWOO Lost City: {} expected starts near the track -> {} of {} buildings in {} ms ({}): {}",
                    starts, picked.size(), all.size(), (System.nanoTime() - t0) / 1_000_000L,
                    Thread.currentThread().getName(), new TreeSet<>(picked));
            return picked;
        } catch (Throwable t) {
            // No foretaste rather than an unconfined one: the Lost City run is unaffected either way.
            LOGGER.error("[DungeonTrain] WWOO Lost City census failed; the WWOO stretch gets no Lost City buildings", t);
            return Set.of();
        }
    }

    /** Lost City starts Lap 1's WWOO band will host within {@link #TRACK_CHUNKS} of the track. */
    static int expectedStarts(ChunkGeneratorStructureState state, long seed, WorldGenCycle cycle) {
        List<StructureSet> sets = new ArrayList<>();
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
            boolean lostCity = set.value().structures().stream()
                    .anyMatch(e -> e.structure().unwrapKey()
                            .map(k -> LostCityStructures.isLostCityStructure(k.location()))
                            .orElse(false));
            if (lostCity) sets.add(set.value());
        }
        if (sets.isEmpty()) return 0;
        int starts = 0;
        for (int chunkX : wwooChunksOfFirstLap(cycle)) {
            for (int chunkZ = -TRACK_CHUNKS; chunkZ <= TRACK_CHUNKS; chunkZ++) {
                if (!LostCityStructures.allowedAt(seed, cycle, chunkX, chunkZ)) continue;
                for (StructureSet set : sets) {
                    if (set.placement().isStructureChunk(state, chunkX, chunkZ)) starts++;
                }
            }
        }
        return starts;
    }

    /** Chunk columns of Lap 1 (the first period after {@code startX}) in a WWOO stretch. */
    static List<Integer> wwooChunksOfFirstLap(WorldGenCycle cycle) {
        List<Integer> chunks = new ArrayList<>();
        if (cycle == null || !cycle.hasLayout()) return chunks;
        long from = cycle.startX() >> 4;
        long to = (cycle.startX() + cycle.period()) >> 4;
        for (long cx = from; cx < to; cx++) {
            if (cx < Integer.MIN_VALUE >> 4 || cx > Integer.MAX_VALUE >> 4) continue;
            if (LostCityStructures.inWwooStretch(cycle, (int) cx)) chunks.add((int) cx);
        }
        return chunks;
    }
}
