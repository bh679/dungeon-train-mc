package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.EndBandConfig;
import games.brennan.dungeontrain.util.LogFirstN;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The real End's structures for a sampled <b>BetterEnd</b> band chunk: its bridges, small islands, lakes,
 * mountains, ice stars, giant glowshrooms, sulphuric caves, villages and End cities.
 *
 * <p>A sample is a throwaway chunk that never went through vanilla's {@code STRUCTURE_STARTS} and
 * {@code STRUCTURE_REFERENCES} steps, so decoration found no starts and built nothing. Those steps would need
 * real neighbours to hold the starts; instead {@link #withStructures} hands decoration a
 * {@link StructureManager} that works out the same answer from the End's own structure sets. Decoration then
 * places the pieces as vanilla does: each step in order, clipped to the chunk, so a bridge carries on across
 * every sample it reaches.</p>
 *
 * <p>A start depends only on its chunk, the End's seed and its biomes, so each chunk's starts are generated
 * once and shared between samples ({@link #STARTS}).</p>
 *
 * <p><b>Left out:</b> {@link #isExcluded Eternal portals}, which are working portals and would lead out of the
 * train's world, and DT's own structures, which belong to the display world.</p>
 */
public final class EndBandStructures {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN SITE_ERRORS = new LogFirstN(5);

    /** How far a start reaches, as in vanilla's {@code createReferences}. */
    public static final int REACH_CHUNKS = 8;

    /** Comfortably more than the view-distance strip of band chunks plus {@link #REACH_CHUNKS} around it. */
    private static final int CACHE_CHUNKS = 8192;

    private static final ResourceLocation ETERNAL_PORTAL =
            ResourceLocation.fromNamespaceAndPath("betterend", "eternal_portal");
    private static final String DT_NAMESPACE = "dungeontrain";

    /** A valid start and the structure it belongs to. */
    private record Placed(Structure structure, StructureStart start) {}

    private static final EndBandGroundCache<List<Placed>> STARTS = new EndBandGroundCache<>(CACHE_CHUNKS);

    private EndBandStructures() {}

    /**
     * {@code workspace} answering structure lookups for End chunk {@code endPos} from the End's structure sets,
     * or unchanged when {@code endBandStructures} is off. {@code biomes} is the band's remapped source; the
     * per-sample memo must not reach here, since a start outlives the sample that first asked for it.
     */
    public static OfflineChunkSampler.Workspace withStructures(ServerLevel end, NoiseBasedChunkGenerator noise,
                                                               RandomState random, BiomeSource biomes, ChunkPos endPos,
                                                               OfflineChunkSampler.Workspace workspace) {
        if (!EndBandConfig.structures()) return workspace;
        Supplier<Map<Structure, List<StructureStart>>> reaching =
                () -> startsReaching(end, noise, random, biomes, endPos);
        StructureManager manager = new SampleStructures(workspace, end, reaching);
        return new OfflineChunkSampler.Workspace(workspace.region(), manager, workspace.biomes());
    }

    /** Forget every start (server stopping / world change). */
    public static void clear() {
        STARTS.clear();
    }

    /** True for a structure the band never builds. */
    static boolean isExcluded(ResourceLocation id) {
        return ETERNAL_PORTAL.equals(id) || DT_NAMESPACE.equals(id.getNamespace());
    }

    /** Whether box {@code [minX, maxX] × [minZ, maxZ]} touches {@code chunk}, as a piece must to be placed there. */
    static boolean reaches(ChunkPos chunk, int minX, int minZ, int maxX, int maxZ) {
        return maxX >= chunk.getMinBlockX() && minX <= chunk.getMaxBlockX()
                && maxZ >= chunk.getMinBlockZ() && minZ <= chunk.getMaxBlockZ();
    }

    /** Every start within {@link #REACH_CHUNKS} whose box touches {@code endPos}, by structure. */
    private static Map<Structure, List<StructureStart>> startsReaching(ServerLevel end, NoiseBasedChunkGenerator noise,
                                                                       RandomState random, BiomeSource biomes, ChunkPos endPos) {
        Map<Structure, List<StructureStart>> out = new IdentityHashMap<>();
        for (int dx = -REACH_CHUNKS; dx <= REACH_CHUNKS; dx++) {
            for (int dz = -REACH_CHUNKS; dz <= REACH_CHUNKS; dz++) {
                ChunkPos at = new ChunkPos(endPos.x + dx, endPos.z + dz);
                List<Placed> placed = STARTS.get(new EndBandGroundCache.Key(CycleLayout.Style.BETTER, at.toLong()),
                        () -> generateStarts(end, noise, random, biomes, at));
                if (placed == null) continue;     // another thread's generation failed; a later sample retries
                for (Placed p : placed) {
                    var box = p.start().getBoundingBox();
                    if (reaches(endPos, box.minX(), box.minZ(), box.maxX(), box.maxZ())) {
                        out.computeIfAbsent(p.structure(), s -> new ArrayList<>()).add(p.start());
                    }
                }
            }
        }
        return out;
    }

    /** What vanilla's {@code ChunkGenerator.createStructures} would start in chunk {@code at}. */
    private static List<Placed> generateStarts(ServerLevel end, NoiseBasedChunkGenerator noise, RandomState random,
                                               BiomeSource biomes, ChunkPos at) {
        ChunkGeneratorStructureState state = end.getChunkSource().getGeneratorState();
        List<Placed> out = new ArrayList<>(0);
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
            try {
                if (!set.value().placement().isStructureChunk(state, at.x, at.z)) continue;
                Placed placed = pick(set.value(), end, noise, random, biomes, state.getLevelSeed(), at);
                if (placed != null) out.add(placed);
            } catch (Throwable t) {
                SITE_ERRORS.error(LOGGER, "[DungeonTrain] End-band structure failed at " + at + "; skipping it", t);
            }
        }
        return List.copyOf(out);
    }

    /** One start from {@code set}: vanilla's weighted draw, falling through to the next entry when one can't site. */
    private static Placed pick(StructureSet set, ServerLevel end, NoiseBasedChunkGenerator noise, RandomState random,
                               BiomeSource biomes, long seed, ChunkPos at) {
        List<StructureSet.StructureSelectionEntry> entries = new ArrayList<>(set.structures().size());
        for (StructureSet.StructureSelectionEntry e : set.structures()) {
            ResourceLocation id = e.structure().unwrapKey().map(k -> k.location()).orElse(null);
            if (id == null || !isExcluded(id)) entries.add(e);
        }
        WorldgenRandom draw = new WorldgenRandom(new LegacyRandomSource(0L));
        draw.setLargeFeatureSeed(seed, at.x, at.z);
        int total = entries.stream().mapToInt(StructureSet.StructureSelectionEntry::weight).sum();
        while (!entries.isEmpty()) {
            int index = weightedIndex(entries, draw.nextInt(total));
            StructureSet.StructureSelectionEntry entry = entries.get(index);
            Structure structure = entry.structure().value();
            StructureStart start = structure.generate(end.registryAccess(), noise, biomes, random,
                    end.getStructureManager(), seed, at, 0, end, structure.biomes()::contains);
            if (start.isValid()) return new Placed(structure, start);
            entries.remove(index);
            total -= entry.weight();
        }
        return null;
    }

    private static int weightedIndex(List<StructureSet.StructureSelectionEntry> entries, int roll) {
        for (int i = 0; i < entries.size(); i++) {
            roll -= entries.get(i).weight();
            if (roll < 0) return i;
        }
        return entries.size() - 1;
    }

    /**
     * Answers only the lookup decoration makes. Worked out on first use so it runs inside
     * {@link OfflineChunkSampler#decorate}, like everything else a sample's features read.
     */
    private static final class SampleStructures extends StructureManager {

        private final Supplier<Map<Structure, List<StructureStart>>> reaching;
        private Map<Structure, List<StructureStart>> starts;

        SampleStructures(OfflineChunkSampler.Workspace workspace, ServerLevel end,
                         Supplier<Map<Structure, List<StructureStart>>> reaching) {
            // No structure check: only locate-style queries read it, and nothing asks those of a sample.
            super(workspace.region(), end.getServer().getWorldData().worldGenOptions(), null);
            this.reaching = reaching;
        }

        @Override
        public List<StructureStart> startsForStructure(SectionPos section, Structure structure) {
            if (starts == null) starts = reaching.get();
            return starts.getOrDefault(structure, List.of());
        }
    }
}
