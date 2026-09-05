package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.MobSpawnSettings;
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
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * <h2>The mobs come with the chunk</h2>
 * <p>A chunk is not just its blocks. The animals a biome starts its chunks with, and the villagers,
 * pillagers or piglins a structure is placed with, are entities the generation passes add through
 * the region — into the same throwaway chunks, where {@link PortalChunkTerrain} reads them back as
 * NBT and the room spawns them for real. So a sampled meadow arrives with its sheep and a sampled
 * outpost with the people who live in it, rather than as scenery.</p>
 *
 * <h2>Always at least one structure</h2>
 * <p>Vanilla puts a structure in something like one chunk in a hundred, so sampling and hoping would
 * make "a chunk with a village in it" a thing a player heard about rather than saw. A structure is
 * therefore <b>chosen</b> for the sample: one of the ones whose own biome list admits the biome that
 * was sampled, generated at the sample's own chunk so it sits on that terrain. The pick is a plain
 * draw from the structures that fit, so what turns up is still the world's own vocabulary — a desert
 * has pyramids and a plain has villages, because that is what their biome lists say.</p>
 *
 * <p>A start that lands outside the rows the room will show is <b>moved onto them</b> rather than
 * discarded. That is the ordinary case away from the Overworld's surface: a fortress or a bastion
 * sits at the Y its own placement wants and an End city stands on an island, while the cube is cut
 * around whichever cavern floor or ledge the sample was anchored on, so the two rarely meet by luck.
 * Moving it is what makes the guarantee hold in all three dimensions instead of mostly holding in
 * one.</p>
 */
final class PortalChunkFeatures {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How many structures are tried before the sample settles for having none. */
    private static final int STRUCTURE_ATTEMPTS = 12;

    /**
     * How often a room's structure is drawn from somewhere else entirely — an End city in a meadow,
     * a desert pyramid in a crimson forest.
     *
     * <p>One in ten, which is the point: often enough that a player who rides long enough meets one
     * and has to work out what they are looking at, rare enough that the other nine read as the
     * dimension they came from.</p>
     */
    private static final float FOREIGN_STRUCTURE_CHANCE = 0.10F;

    /**
     * How many times the biome's own creature pass is rolled before a room settles for having no
     * animals — see {@link #decorate}.
     *
     * <p>A biome with no creature spawns at all, which is both the Nether and the End, comes back
     * empty from every one of them and costs nothing for trying: the pass returns immediately on an
     * empty spawn list.</p>
     */
    private static final int MOB_SPAWN_ATTEMPTS = 12;

    /** How many of the biome's monsters a room is given. */
    private static final int MONSTERS_PER_ROOM = 3;

    /** How many positions are tried to place them — most rooms have plenty, a cave floor fewer. */
    private static final int MONSTER_ATTEMPTS = 24;

    private PortalChunkFeatures() {}

    /**
     * Cut the caves into {@code chunk} in place — the half of the remaining generation that changes
     * the <b>shape</b> of the ground.
     *
     * <p>Split from {@link #decorate} because of what each costs and what each moves. A room's two
     * doorways are stood on the ground the sample landed, and a carver can take that ground away, so
     * carving has to happen before the doors are fitted — which means before the pair can be planned
     * at all, and therefore in the handful of milliseconds a portal carriage can wait without
     * refusing to cross. Structures and features add to a room without moving its floor, and they
     * are the expensive ones, so they follow afterwards.</p>
     *
     * <p>Best-effort: anything that throws leaves the sample as the terrain it already was, which is
     * a room, rather than failing the pair. Runs on the sampling worker.</p>
     */
    static void carve(NoiseBasedChunkGenerator generator, ServerLevel level, RandomState random,
                      ProtoChunk chunk, Workspace workspace, long worldSeed, int pairKey) {
        try {
            generator.applyCarvers(workspace.region(), worldSeed, random,
                biomeManager(generator, random, worldSeed), workspace.structures(), chunk,
                GenerationStep.Carving.AIR);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Chunk dimension carving failed for pair {} — the room keeps "
                + "its uncarved terrain", pairKey, t);
        }
    }

    /**
     * Place a structure into {@code chunk} and decorate it in place — everything that grows on the
     * ground rather than shaping it.
     *
     * <p>Runs after the room is already standing, and is written into it as a second pass, because
     * this is seconds of work and a portal carriage that has not finished it is a carriage that will
     * not cross. Nothing here moves a doorway, so a room gaining its trees a moment after a player
     * walks in is a room growing, not a room changing under them.</p>
     *
     * @param window the rows of {@code chunk} the room will actually show, so a structure that lands
     *               entirely outside them can be rejected in favour of one that does not
     */
    static void decorate(NoiseBasedChunkGenerator generator, ServerLevel level, RandomState random,
                         ProtoChunk chunk, Workspace workspace, BoundingBox window, long worldSeed,
                         int pairKey) {
        try {
            plantStructure(level, generator, random, chunk, window, worldSeed, pairKey);
            // Places the structure registered above along with everything else the biome grows.
            generator.applyBiomeDecoration(workspace.region(), chunk, workspace.structures());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Chunk dimension decoration failed for pair {} — the room "
                + "keeps its bare terrain", pairKey, t);
        }
    }

    /**
     * The region a sample is generated in and the structure manager bound to it — built once per
     * sample and handed to every pass.
     *
     * <p>The manager is what {@code fillFromNoise} needs to bear terrain down under whatever was
     * built there. Handing it the level's own instead would have it read structure starts out of the
     * world — one chunk load at a time, on a worker thread, for a chunk sixty thousand chunks from
     * anything.</p>
     */
    record Workspace(WorldGenRegion region, StructureManager structures) {}

    /** A workspace over {@code chunk} and the ring of throwaway neighbours around it. */
    static Workspace workspaceFor(ServerLevel level, NoiseBasedChunkGenerator generator,
                                  RandomState random, ProtoChunk chunk) {
        WorldGenRegion region = regionAround(level, generator, random, chunk,
            ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES));
        return new Workspace(region, level.structureManager().forWorldGenRegion(region));
    }

    /** A biome manager reading the generator's own biome source rather than any level's chunks. */
    private static BiomeManager biomeManager(NoiseBasedChunkGenerator generator, RandomState random,
                                             long worldSeed) {
        return new BiomeManager(
            (x, y, z) -> generator.getBiomeSource().getNoiseBiome(x, y, z, random.sampler()),
            BiomeManager.obfuscateSeed(worldSeed));
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
                // Blank, and deliberately cheap: a neighbour is only here to catch what a feature at
                // the middle chunk's edge writes past it, and it is thrown away a moment later.
                // Saying it has reached SURFACE is enough to stop vanilla refusing to answer for it;
                // sampling its biomes as well cost more than generating the chunk anybody sees, four
                // hundred climate lookups at a time, eight times over, for every candidate site.
                ProtoChunk blank = new ProtoChunk(pos, UpgradeData.EMPTY, level, biomeRegistry, null);
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
        // Deterministic in the seed and the pair, like every other choice a pair makes.
        Random rng = new Random(worldSeed ^ ((long) pairKey * 0x9E3779B97F4A7C15L));
        boolean foreign = rng.nextFloat() < FOREIGN_STRUCTURE_CHANCE;
        List<Structure> candidates = foreign
            ? foreignStructures(level, chunk, window)
            : fittingStructures(level, chunk, window);
        // A dimension whose biomes admit everything in the registry has nothing foreign to offer,
        // and a sample nothing admits has nothing native to — either way the other list stands in
        // rather than the room going without.
        if (candidates.isEmpty()) {
            candidates = foreign
                ? fittingStructures(level, chunk, window)
                : foreignStructures(level, chunk, window);
            foreign = !foreign;
        }
        if (candidates.isEmpty()) {
            LOGGER.warn("[DungeonTrain] Chunk dimension pair {} has no structure to plant — nothing "
                + "in the registry admits the biome it sampled", pairKey);
            return;
        }

        StructureStart fallback = null;
        for (int attempt = 0; attempt < STRUCTURE_ATTEMPTS && !candidates.isEmpty(); attempt++) {
            Structure structure = candidates.remove(rng.nextInt(candidates.size()));
            StructureStart start = structure.generate(
                level.registryAccess(), generator, generator.getBiomeSource(), random,
                level.getStructureManager(), worldSeed, chunk.getPos(), /*references*/ 0, chunk,
                // Every candidate already admits this biome; asked again per piece, a jigsaw's
                // outlying pieces can veto the whole start for landing one chunk over.
                biome -> true);
            if (!start.isValid()) continue;
            if (spanOf(start).intersects(window)) {
                register(chunk, start);
                LOGGER.info("[DungeonTrain] Chunk dimension pair {} planted {}{} where it generated",
                    pairKey, nameOf(level, structure), foreign ? " (from another dimension)" : "");
                return;
            }
            if (fallback == null) fallback = start;
        }

        // Nothing generated where the room can see it. That is the ordinary case in the Nether and
        // the End rather than a rarity: a fortress or a bastion sits at the Y its own placement
        // wants, and an End city stands on an island, while the cube is cut around whichever cavern
        // floor or ledge the sample was anchored on — so the two rarely meet by luck. The structure
        // is moved onto the room's ground instead of being thrown away, which is what makes "always
        // at least one structure" true in all three dimensions rather than mostly true in one.
        if (fallback == null) {
            LOGGER.warn("[DungeonTrain] Chunk dimension pair {} planted nothing — {} candidate(s), "
                + "none of them generated a valid start", pairKey, STRUCTURE_ATTEMPTS);
            return;
        }
        BoundingBox span = spanOf(fallback);
        int lift = window.minY() + PortalChunkTerrain.SURFACE_ROW - span.minY();
        fallback.getPieces().forEach(piece -> piece.move(0, lift, 0));
        register(chunk, fallback);
        LOGGER.info("[DungeonTrain] Chunk dimension pair {} planted {}{}, moved {} blocks onto the "
            + "room's ground", pairKey, nameOf(level, fallback.getStructure()),
            foreign ? " (from another dimension)" : "", lift);
    }

    /** What a structure is called, for the log — its registry id, or its class when unregistered. */
    private static String nameOf(ServerLevel level, Structure structure) {
        var id = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(structure);
        return id == null ? structure.getClass().getSimpleName() : id.toString();
    }

    /** Put a start on the chunk, with the reference that makes the decoration pass place it. */
    private static void register(ProtoChunk chunk, StructureStart start) {
        chunk.setStartForStructure(start.getStructure(), start);
        chunk.addReferenceForStructure(start.getStructure(), chunk.getPos().toLong());
    }

    /**
     * The box a start's pieces actually occupy, computed rather than read off the start.
     *
     * <p>{@link StructureStart#getBoundingBox()} memoises, and these pieces get moved — so asking it
     * once before a move and again afterwards answers the same stale box both times.</p>
     */
    private static BoundingBox spanOf(StructureStart start) {
        BoundingBox span = null;
        for (StructurePiece piece : start.getPieces()) {
            span = span == null ? piece.getBoundingBox() : span.encapsulate(piece.getBoundingBox());
        }
        return span == null ? start.getBoundingBox() : span;
    }

    /**
     * Every structure whose own biome list admits a biome the sample actually contains.
     *
     * <p>Read across the chunk rather than off its middle column, which is what starved the End: an
     * island's edge is {@code the_end} — the void biome, which no structure admits — a few blocks
     * from the {@code end_highlands} an End city wants, and a single column lands on one or the
     * other. Sampling the biome container's own grid asks the question the chunk can actually
     * answer.</p>
     */
    private static List<Structure> fittingStructures(ServerLevel level, ProtoChunk chunk,
                                                     BoundingBox window) {
        Set<Holder<Biome>> present = biomesIn(chunk, window);
        List<Structure> fitting = new ArrayList<>();
        for (Structure structure : level.registryAccess().registryOrThrow(Registries.STRUCTURE)) {
            if (admitsAny(structure, present)) fitting.add(structure);
        }
        return fitting;
    }

    /**
     * Every structure that belongs to <b>another dimension</b>: one admitted by the biomes of a
     * dimension this sample did not come from, and not by its own.
     *
     * <p>What the one-in-ten roll draws from — an End city standing in a meadow, a bastion in a
     * birch forest, a village in the crimson. Asked of the other dimensions' biome sources rather
     * than of "anything this chunk does not admit", which is a different and much duller question:
     * a swamp's ruined portal is not admitted by a mountain either, and a room that borrowed one
     * would have borrowed nothing a player could notice.</p>
     *
     * <p>Both halves matter. A structure has to belong somewhere else, and it has to not belong
     * here — Dungeon Train hands Nether structures to its own overworld Nether band, so a fortress
     * is native to a good deal of this world's surface and would otherwise be counted as a
     * traveller in the one place it lives.</p>
     */
    private static List<Structure> foreignStructures(ServerLevel level, ProtoChunk chunk,
                                                     BoundingBox window) {
        Set<Holder<Biome>> present = biomesIn(chunk, window);
        Set<Holder<Biome>> elsewhere = otherDimensionBiomes(level);
        if (elsewhere.isEmpty()) return List.of();
        List<Structure> foreign = new ArrayList<>();
        for (Structure structure : level.registryAccess().registryOrThrow(Registries.STRUCTURE)) {
            if (admitsAny(structure, elsewhere) && !admitsAny(structure, present)) {
                foreign.add(structure);
            }
        }
        return foreign;
    }

    /** Every biome the <b>other</b> dimensions can generate, read off their own biome sources. */
    private static Set<Holder<Biome>> otherDimensionBiomes(ServerLevel level) {
        Set<Holder<Biome>> out = new java.util.LinkedHashSet<>();
        if (level.getServer() == null) return out;
        for (PortalChunkTerrain.Source source : PortalChunkTerrain.Source.values()) {
            if (source.levelKey().equals(level.dimension())) continue;
            ServerLevel other = level.getServer().getLevel(source.levelKey());
            if (other == null) continue;
            out.addAll(other.getChunkSource().getGenerator().getBiomeSource().possibleBiomes());
        }
        return out;
    }

    private static boolean admitsAny(Structure structure, Set<Holder<Biome>> biomes) {
        for (Holder<Biome> biome : biomes) {
            if (structure.biomes().contains(biome)) return true;
        }
        return false;
    }

    /** The biomes the sample holds across the rows the room will show, on the container's own grid. */
    private static Set<Holder<Biome>> biomesIn(ProtoChunk chunk, BoundingBox window) {
        Set<Holder<Biome>> present = new java.util.LinkedHashSet<>();
        int minQuartX = QuartPos.fromBlock(chunk.getPos().getMinBlockX());
        int minQuartZ = QuartPos.fromBlock(chunk.getPos().getMinBlockZ());
        for (int qx = 0; qx < 4; qx++) {
            for (int qz = 0; qz < 4; qz++) {
                for (int y = window.minY(); y <= window.maxY(); y += 8) {
                    present.add(chunk.getNoiseBiome(
                        minQuartX + qx, QuartPos.fromBlock(y), minQuartZ + qz));
                }
            }
        }
        return present;
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
