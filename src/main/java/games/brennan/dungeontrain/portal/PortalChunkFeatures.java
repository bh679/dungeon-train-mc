package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Running the rest of world generation over a {@link PortalChunkTerrain} sample: the carvers, one
 * guaranteed structure, and every feature the biome would have decorated the chunk with.
 *
 * <h2>A world generation region over chunks that belong to nobody</h2>
 * <p>Features and structures need a {@link net.minecraft.world.level.WorldGenLevel}, and the obvious
 * one — the live server level the room is stamped into — is the wrong one twice over. Its heightmaps
 * are the train's surface eighty blocks overhead, so every heightmap-relative placement (which is
 * most of them: trees, grass, boulders, snow) would land on the world the train is running through
 * rather than in the room. And nothing would bound the writes, so a tree at the edge of the sample
 * would spill into the sealed basement and stay there.</p>
 *
 * <p>So the region is built over the sample itself: a {@link WorldGenRegion} whose chunks are the
 * throwaway {@link ProtoChunk} the terrain was sampled into, ringed by empty ones. Heightmaps are
 * the sample's, and anything that reaches past the middle chunk writes into a neighbour that is
 * discarded a moment later — containment for free, rather than a sweep afterwards. Still no chunk in
 * any world is loaded, generated or saved.</p>
 *
 * <h2>Always at least one structure</h2>
 * <p>Vanilla puts a structure in something like one chunk in a hundred, so sampling and hoping would
 * make "a chunk with a village in it" a thing a player heard about rather than saw. A structure is
 * therefore <b>chosen</b> for the sample: one of the ones whose own biome list admits the biome that
 * was sampled, generated at the sample's own chunk so it sits on that terrain, and kept only if what
 * it produced actually reaches the slice a player will walk into. The pick is a plain draw from the
 * structures that fit, so what turns up is still the world's own vocabulary — a desert has pyramids
 * and a plain has villages, because that is what their biome lists say.</p>
 */
final class PortalChunkFeatures {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How many structures are tried before the sample settles for having none. */
    private static final int STRUCTURE_ATTEMPTS = 12;

    private PortalChunkFeatures() {}

    /**
     * Carve, place a structure into, and decorate {@code chunk} in place.
     *
     * <p>Best-effort: anything that throws leaves the sample as the bare terrain it already was,
     * which is a room, rather than failing the pair. Runs on the sampling worker.</p>
     *
     * @param window the rows of {@code chunk} the room will actually show, so a structure that lands
     *               entirely outside them can be rejected in favour of one that does not
     */
    static void decorate(NoiseBasedChunkGenerator generator, ServerLevel level, RandomState random,
                         ProtoChunk chunk, BoundingBox window, long worldSeed, int pairKey) {
        try {
            ChunkStep step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES);
            WorldGenRegion region = regionAround(level, generator, random, chunk, step);
            StructureManager structures = level.structureManager().forWorldGenRegion(region);
            BiomeManager biomes = new BiomeManager(
                (x, y, z) -> generator.getBiomeSource().getNoiseBiome(x, y, z, random.sampler()),
                BiomeManager.obfuscateSeed(worldSeed));

            // Caves first, as vanilla does: a feature decorating a hole the carvers had not dug yet
            // would be furnishing a wall.
            generator.applyCarvers(region, worldSeed, random, biomes, structures, chunk,
                GenerationStep.Carving.AIR);

            plantStructure(level, generator, random, chunk, window, worldSeed, pairKey);

            // Places the structure registered above along with everything else the biome grows.
            generator.applyBiomeDecoration(region, chunk, structures);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Chunk dimension decoration failed for pair {} — the room "
                + "keeps its bare terrain", pairKey, t);
        }
    }

    /**
     * A region centred on {@code chunk}, ringed by empty chunks out to the step's own radius.
     *
     * <p>The neighbours are deliberately blank. Filling them would double or triple the sampling for
     * terrain nobody sees — the room takes the middle chunk and nothing else — and their only job
     * here is to catch what a feature at the edge writes past it.</p>
     */
    private static WorldGenRegion regionAround(ServerLevel level, NoiseBasedChunkGenerator generator,
                                               RandomState random, ProtoChunk chunk, ChunkStep step) {
        ChunkPos centre = chunk.getPos();
        int radius = Math.max(1, step.accumulatedDependencies().getRadius());
        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        StaticCache2D<GenerationChunkHolder> cache = StaticCache2D.create(
            centre.x, centre.z, radius, (x, z) -> {
                ChunkPos pos = new ChunkPos(x, z);
                if (pos.equals(centre)) return new SampleHolder(pos, chunk);
                // Blank, but not so blank that asking it a question throws: a neighbour still has to
                // answer for its biomes and say it has got as far as the middle one has.
                ProtoChunk blank = new ProtoChunk(pos, UpgradeData.EMPTY, level, biomeRegistry, null);
                blank.fillBiomesFromNoise(generator.getBiomeSource(), random.sampler());
                blank.setPersistedStatus(ChunkStatus.SURFACE);
                return new SampleHolder(pos, blank);
            });
        return new WorldGenRegion(level, cache, step, chunk);
    }

    /**
     * Choose a structure the sampled biome admits, generate it on the sample's own terrain, and
     * register it on the chunk so the decoration pass places it.
     *
     * <p>Kept only when its bounding box reaches {@code window} — the rows the room will show. A
     * mineshaft four hundred blocks down is a structure the sample technically has and a player
     * never sees, and taking it would spend the one guarantee on nothing.</p>
     */
    private static void plantStructure(ServerLevel level, NoiseBasedChunkGenerator generator,
                                       RandomState random, ProtoChunk chunk, BoundingBox window,
                                       long worldSeed, int pairKey) {
        List<Structure> candidates = fittingStructures(level, generator, chunk);
        if (candidates.isEmpty()) return;

        // Deterministic in the seed and the pair, like every other choice a pair makes, so a
        // re-sampled room is the same room.
        Random rng = new Random(worldSeed ^ ((long) pairKey * 0x9E3779B97F4A7C15L));
        for (int attempt = 0; attempt < STRUCTURE_ATTEMPTS && !candidates.isEmpty(); attempt++) {
            Structure structure = candidates.remove(rng.nextInt(candidates.size()));
            StructureStart start = structure.generate(
                level.registryAccess(), generator, generator.getBiomeSource(), random,
                level.getStructureManager(), worldSeed, chunk.getPos(), /*references*/ 0, chunk,
                // Every candidate already admits this biome; asked again per piece, a jigsaw's
                // outlying pieces can veto the whole start for landing one chunk over.
                biome -> true);
            if (!start.isValid()) continue;
            if (!start.getBoundingBox().intersects(window)) continue;
            chunk.setStartForStructure(structure, start);
            chunk.addReferenceForStructure(structure, chunk.getPos().toLong());
            return;
        }
    }

    /** Every structure whose own biome list admits the biome at the sample's surface. */
    private static List<Structure> fittingStructures(ServerLevel level,
                                                     NoiseBasedChunkGenerator generator,
                                                     ProtoChunk chunk) {
        Holder<Biome> biome = chunk.getNoiseBiome(
            net.minecraft.core.QuartPos.fromBlock(chunk.getPos().getMiddleBlockX()),
            net.minecraft.core.QuartPos.fromBlock(
                chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,
                    chunk.getPos().getMiddleBlockX(), chunk.getPos().getMiddleBlockZ())),
            net.minecraft.core.QuartPos.fromBlock(chunk.getPos().getMiddleBlockZ()));
        List<Structure> fitting = new ArrayList<>();
        for (Structure structure : level.registryAccess().registryOrThrow(Registries.STRUCTURE)) {
            if (structure.biomes().contains(biome)) fitting.add(structure);
        }
        return fitting;
    }

    /**
     * The least a {@link WorldGenRegion} will accept as a chunk holder: one chunk, always present,
     * never scheduled.
     *
     * <p>A real holder is the chunk system's own bookkeeping — ticket levels, generation futures, a
     * queue position. None of that exists for a sample nobody asked the chunk system for, and none
     * of it is read on the path a feature takes to a block.</p>
     */
    private static final class SampleHolder extends GenerationChunkHolder {

        private final ChunkAccess chunk;

        private SampleHolder(ChunkPos pos, ChunkAccess chunk) {
            super(pos);
            this.chunk = chunk;
        }

        @Override
        public ChunkAccess getChunkIfPresent(ChunkStatus status) {
            return chunk;
        }

        @Override
        public ChunkAccess getChunkIfPresentUnchecked(ChunkStatus status) {
            return chunk;
        }

        @Override
        public ChunkAccess getLatestChunk() {
            return chunk;
        }

        @Override
        public ChunkStatus getPersistedStatus() {
            return ChunkStatus.FEATURES;
        }

        @Override
        public int getTicketLevel() {
            return 0;
        }

        @Override
        public int getQueueLevel() {
            return 0;
        }
    }
}
