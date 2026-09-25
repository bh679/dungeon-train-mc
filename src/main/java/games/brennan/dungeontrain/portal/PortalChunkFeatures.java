package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.worldgen.OfflineChunkSampler;
import games.brennan.dungeontrain.worldgen.VanillaOnlySample;
import games.brennan.dungeontrain.worldgen.VanillaBiomeTwins;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ProtoChunk;
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
     * How often a vanilla End room's structure is the End's own — an End city — rather than one
     * from another dimension. Set rather than left to the draw: the End city is the vanilla End's
     * only structure and turns down most of the islands a sample lands on, so left alone about four
     * rooms in five fell through to another dimension's structure and the End barely read as itself.
     */
    private static final float VANILLA_END_NATIVE_CHANCE = 0.60F;

    /** How many other End islands an End city is tried on when the sampled one turns it down. */
    private static final int RELOCATE_ATTEMPTS = 64;

    /** Where those islands are looked for: past the main island, out across the outer End. */
    private static final int RELOCATE_MIN_BLOCKS = 1_536;
    private static final int RELOCATE_SPREAD_BLOCKS = 40_000;

    /**
     * How many times the biome's own creature pass is rolled before a room settles for having no
     * animals — see {@link #decorate}.
     *
     * <p>A biome with no creature spawns at all, which is both the Nether and the End, comes back
     * empty from every one of them and costs nothing for trying: the pass returns immediately on an
     * empty spawn list.</p>
     */
    private static final int MOB_SPAWN_ATTEMPTS = 12;

    /** How many of the biome's own inhabitants a room is given, where it has any to give. */
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
                      ProtoChunk chunk, OfflineChunkSampler.Workspace workspace, long worldSeed, int pairKey) {
        try {
            OfflineChunkSampler.carve(generator, random, chunk, workspace, worldSeed);
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
     * @param source which room this is; the vanilla Nether and End rooms plant only {@code minecraft:}
     *               structures and place only {@code minecraft:} features — see {@link VanillaOnlySample}
     */
    static void decorate(NoiseBasedChunkGenerator generator, ServerLevel level, RandomState random,
                         ProtoChunk chunk, OfflineChunkSampler.Workspace workspace, BoundingBox window, long worldSeed,
                         int pairKey, PortalChunkTerrain.Source source) {
        boolean vanillaOnly = source.vanillaOnly();
        try {
            plantStructure(level, generator, random, chunk, window, worldSeed, pairKey, source);
            // Places the structure registered above along with everything else the biome grows —
            // and, with it, whatever that structure is inhabited by: a village's villagers, an
            // outpost's pillagers, a bastion's piglins all come through this same region and land in
            // the same throwaway chunk the room is read out of.
            OfflineChunkSampler.decorate(generator, workspace, chunk, vanillaOnly);

            // The pass that puts a fresh chunk's animals in it — the herd of sheep on the hillside,
            // the pigs in the wood.
            //
            // Asked repeatedly, and that is deliberate. Vanilla rolls it once against the biome's
            // creature probability — about one chunk in ten — because a world has millions of chunks
            // and only needs animals in some of them. A chunk dimension has exactly one chunk, and
            // the room a player walks into is the whole of what they will see of that biome, so nine
            // rooms in ten arriving empty is the wrong end of that trade. Each call re-seeds itself,
            // so this is the same distribution asked more often rather than a different one, and a
            // biome with no creature spawns — the Nether and the End — returns immediately from
            // every attempt.
            for (int attempt = 0; attempt < MOB_SPAWN_ATTEMPTS && chunk.getEntities().isEmpty();
                    attempt++) {
                generator.spawnOriginalMobs(workspace.region());
            }
            // Only where the passes above left the room empty. An Overworld chunk keeps its sheep
            // and its structure's people and gets nothing else; a Nether one, whose animals are
            // striders that need a lava lake to stand in and rarely find one, falls through to what
            // else lives there.
            if (chunk.getEntities().isEmpty()) {
                spawnBiomeNatives(level, workspace.region(), chunk, window, worldSeed, pairKey);
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Chunk dimension decoration failed for pair {} — the room "
                + "keeps its bare terrain", pairKey, t);
        }
    }

    /**
     * Put a few of the sampled biome's own inhabitants in the room — whatever that biome actually
     * spawns.
     *
     * <p><b>Passive where there is passive, hostile only where there is nothing else.</b> An
     * Overworld room should be the sheep and the pigs that live in that meadow plus whoever came
     * with its structure — not a meadow with three zombies standing in it — so this runs only when
     * the passes before it left the room empty.</p>
     *
     * <p>Emptiness, and deliberately not "this biome has no animals to offer": the Nether's biomes
     * <i>do</i> carry a creature list, and it is the strider, which spawns standing in lava and so
     * almost never places in a sampled chunk. Asked whether animals were available, the Nether says
     * yes and stays empty. Asked whether any landed, it says no and gets its zombified piglins.</p>
     *
     * <p><b>Why the game cannot be left to do it.</b> Ongoing spawning asks the biome at the position
     * being spawned into, and a chunk dimension's room sits in the sealed basement under the train —
     * so the answer is the plains the train is crossing, not the crimson forest the room is a slice
     * of. The sample spawns them itself, at generation and once: they live in the room from the
     * moment it is built, they can be killed, and nothing replaces them.</p>
     */
    private static void spawnBiomeNatives(ServerLevel level, WorldGenRegion region, ProtoChunk chunk,
                                          BoundingBox window, long worldSeed, int pairKey) {
        RandomSource random = RandomSource.create(worldSeed ^ ((long) pairKey * 0xC2B2AE3D27D4EB4FL));
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int placed = 0;
        int noRoom = 0;
        int noMobs = 0;
        for (int attempt = 0; attempt < MONSTER_ATTEMPTS && placed < MONSTERS_PER_ROOM; attempt++) {
            int x = chunk.getPos().getMinBlockX() + random.nextInt(PortalChunkTerrain.SIZE);
            int z = chunk.getPos().getMinBlockZ() + random.nextInt(PortalChunkTerrain.SIZE);
            int y = standingRoom(chunk, cursor, x, z, window);
            if (y == Integer.MIN_VALUE) {
                noRoom++;
                continue;
            }

            Holder<Biome> biome = chunk.getNoiseBiome(
                QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
            // Vanilla's list at a vanilla site, as the world's own chunks get.
            MobSpawnSettings spawns = VanillaBiomeTwins.mobSettingsAt(biome.value(), x);
            Optional<MobSpawnSettings.SpawnerData> pick =
                spawns.getMobs(MobCategory.MONSTER).getRandom(random);
            if (pick.isEmpty()) {
                noMobs++;
                continue;
            }
            if (!(pick.get().type.create(level) instanceof Mob mob)) continue;

            cursor.set(x, y, z);
            mob.moveTo(x + 0.5, y, z + 0.5, random.nextFloat() * 360.0F, 0.0F);
            mob.finalizeSpawn(region, level.getCurrentDifficultyAt(cursor),
                MobSpawnType.CHUNK_GENERATION, null);
            region.addFreshEntity(mob);
            placed++;
        }
        if (placed < MONSTERS_PER_ROOM) {
            LOGGER.info("[DungeonTrain] Chunk dimension pair {} placed {} of {} natives — {} "
                    + "position(s) had nowhere to stand, {} had an empty spawn list",
                pairKey, placed, MONSTERS_PER_ROOM, noRoom, noMobs);
        }
    }

    /** A row inside the window with ground under it and room to stand, or {@code MIN_VALUE}. */
    private static int standingRoom(ProtoChunk chunk, BlockPos.MutableBlockPos cursor, int x, int z,
                                    BoundingBox window) {
        for (int y = window.maxY() - 1; y > window.minY(); y--) {
            if (!chunk.getBlockState(cursor.set(x, y - 1, z)).blocksMotion()) continue;
            if (!chunk.getBlockState(cursor.set(x, y, z)).isAir()) continue;
            if (!chunk.getBlockState(cursor.set(x, y + 1, z)).isAir()) continue;
            return y;
        }
        return Integer.MIN_VALUE;
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
                                       long worldSeed, int pairKey, PortalChunkTerrain.Source source) {
        // Deterministic in the seed and the pair, like every other choice a pair makes.
        Random rng = new Random(worldSeed ^ ((long) pairKey * 0x9E3779B97F4A7C15L));
        boolean vanillaEnd = source == PortalChunkTerrain.Source.END;
        boolean foreign = vanillaEnd
            ? rng.nextFloat() >= VANILLA_END_NATIVE_CHANCE
            : rng.nextFloat() < FOREIGN_STRUCTURE_CHANCE;
        // The drawn list first, then the other one. A dimension whose biomes admit everything in the
        // registry has nothing foreign to offer, a sample nothing admits has nothing native to, and
        // a native list can have nothing that generates here at all. In each case the other list
        // stands in rather than the room going without.
        Planting planting = plantFrom(foreign, level, generator, random, chunk, window, worldSeed,
            pairKey, rng, source);
        if (planting.start() == null) {
            foreign = !foreign;
            planting = plantFrom(foreign, level, generator, random, chunk, window, worldSeed,
                pairKey, rng, source);
        }
        if (planting.start() == null) {
            LOGGER.warn("[DungeonTrain] Chunk dimension pair {} planted nothing — no candidate in either "
                + "list generated a valid start", pairKey);
            return;
        }
        StructureStart start = planting.start();
        if (planting.inWindow()) {
            register(chunk, start);
            LOGGER.info("[DungeonTrain] Chunk dimension pair {} planted {}{} where it generated",
                pairKey, nameOf(level, start.getStructure()), foreign ? " (from another dimension)" : "");
            return;
        }

        // Nothing generated where the room can see it. That is the ordinary case in the Nether and
        // the End rather than a rarity: a fortress or a bastion sits at the Y its own placement
        // wants, and an End city stands on an island, while the cube is cut around whichever cavern
        // floor or ledge the sample was anchored on — so the two rarely meet by luck. The structure
        // is moved onto the room's ground instead of being thrown away, which is what makes "always
        // at least one structure" true in all three dimensions rather than mostly true in one.
        BoundingBox span = spanOf(start);
        int lift = window.minY() + PortalChunkTerrain.SURFACE_ROW - span.minY();
        start.getPieces().forEach(piece -> piece.move(0, lift, 0));
        register(chunk, start);
        LOGGER.info("[DungeonTrain] Chunk dimension pair {} planted {}{}, moved {} blocks onto the "
            + "room's ground", pairKey, nameOf(level, start.getStructure()),
            foreign ? " (from another dimension)" : "", lift);
    }

    /**
     * What one list of candidates came to: a start that reaches the room's rows, else the first
     * valid start that did not (to be moved onto them), else nothing.
     */
    private record Planting(StructureStart start, boolean inWindow) {}

    /**
     * Try the foreign list or the native one. The vanilla End room's native list is every structure
     * the End itself generates, and an End city the sampled island turns down is grown on another
     * island and brought here — see {@link #relocated}.
     */
    private static Planting plantFrom(boolean foreign, ServerLevel level,
                                      NoiseBasedChunkGenerator generator, RandomState random,
                                      ProtoChunk chunk, BoundingBox window, long worldSeed, int pairKey,
                                      Random rng, PortalChunkTerrain.Source source) {
        if (foreign) {
            return tryPlant(level, generator, random, chunk, window, worldSeed, pairKey, rng,
                foreignStructures(level, chunk, window, source));
        }
        if (source != PortalChunkTerrain.Source.END) {
            return tryPlant(level, generator, random, chunk, window, worldSeed, pairKey, rng,
                fittingStructures(level, chunk, window, source.vanillaOnly()));
        }
        List<Structure> native_ = dimensionStructures(level, source);
        Planting here = tryPlant(level, generator, random, chunk, window, worldSeed, pairKey, rng,
            new ArrayList<>(native_));
        return here.start() != null ? here
            : relocated(level, generator, random, chunk, worldSeed, pairKey, rng, native_);
    }

    /**
     * Generate one of {@code candidates} on another island of the same End and move it onto this
     * sample's chunk — the answer when the sampled island is one an End city will not stand on.
     *
     * <p>An End city wants ground at least sixty blocks up under its whole footprint, and most of the
     * islands a room is cut from are lower or smaller than that. The structure is the End's own and
     * so is the island it grew on; only where it stands has changed, which the room already does to
     * any structure that generated above or below the rows it shows.</p>
     */
    private static Planting relocated(ServerLevel level, NoiseBasedChunkGenerator generator,
                                      RandomState random, ProtoChunk chunk, long worldSeed, int pairKey,
                                      Random rng, List<Structure> candidates) {
        for (Structure structure : candidates) {
            for (int attempt = 0; attempt < RELOCATE_ATTEMPTS; attempt++) {
                double angle = rng.nextDouble() * Math.PI * 2.0;
                int radius = RELOCATE_MIN_BLOCKS + rng.nextInt(RELOCATE_SPREAD_BLOCKS);
                ChunkPos at = new ChunkPos(new BlockPos((int) (Math.cos(angle) * radius), 0,
                    (int) (Math.sin(angle) * radius)));
                StructureStart start;
                try {
                    start = structure.generate(level.registryAccess(), generator,
                        generator.getBiomeSource(), random, level.getStructureManager(), worldSeed, at,
                        /*references*/ 0, chunk, biome -> true);
                } catch (RuntimeException e) {
                    LOGGER.warn("[DungeonTrain] Chunk dimension pair {} could not grow {} on another "
                        + "island ({})", pairKey, nameOf(level, structure), e.toString());
                    break;
                }
                if (!start.isValid()) continue;
                int dx = chunk.getPos().getMinBlockX() - at.getMinBlockX();
                int dz = chunk.getPos().getMinBlockZ() - at.getMinBlockZ();
                start.getPieces().forEach(piece -> piece.move(dx, 0, dz));
                LOGGER.debug("[DungeonTrain] Chunk dimension pair {} grew {} at {} after {} island(s)",
                    pairKey, nameOf(level, structure), at, attempt + 1);
                return new Planting(start, false);
            }
        }
        return new Planting(null, false);
    }

    /**
     * Draw up to {@link #STRUCTURE_ATTEMPTS} of {@code candidates} and generate each at the sample,
     * stopping at the first whose pieces reach {@code window}.
     */
    private static Planting tryPlant(ServerLevel level, NoiseBasedChunkGenerator generator,
                                     RandomState random, ProtoChunk chunk, BoundingBox window,
                                     long worldSeed, int pairKey, Random rng, List<Structure> candidates) {
        StructureStart fallback = null;
        for (int attempt = 0; attempt < STRUCTURE_ATTEMPTS && !candidates.isEmpty(); attempt++) {
            Structure structure = candidates.remove(rng.nextInt(candidates.size()));
            StructureStart start;
            try {
                start = structure.generate(
                    level.registryAccess(), generator, generator.getBiomeSource(), random,
                    level.getStructureManager(), worldSeed, chunk.getPos(), /*references*/ 0, chunk,
                    // Every candidate already admits this biome; asked again per piece, a jigsaw's
                    // outlying pieces can veto the whole start for landing one chunk over.
                    biome -> true);
            } catch (RuntimeException e) {
                // A modded structure can assume a live level behind it — BetterNether's city reads a
                // generator it only builds once a real Nether loads — and throws here. That is one
                // candidate that cannot plant, not a reason to lose the room's whole decoration pass.
                LOGGER.warn("[DungeonTrain] Chunk dimension pair {} skipped {}: it could not generate "
                    + "in a sampled chunk ({})", pairKey, nameOf(level, structure), e.toString());
                continue;
            }
            if (!start.isValid()) continue;
            if (spanOf(start).intersects(window)) return new Planting(start, true);
            if (fallback == null) fallback = start;
        }
        return new Planting(fallback, false);
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
                                                     BoundingBox window, boolean vanillaOnly) {
        Set<Holder<Biome>> present = biomesIn(chunk, window);
        List<Structure> fitting = new ArrayList<>();
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (Structure structure : registry) {
            if (vanillaOnly && !VanillaOnlySample.allows(registry.getKey(structure))) continue;
            if (admitsAny(structure, present)) fitting.add(structure);
        }
        return fitting;
    }

    /**
     * Every structure {@code source}'s own dimension generates anywhere — admitted by any biome its
     * generator can place, not just the ones in this sample.
     */
    private static List<Structure> dimensionStructures(ServerLevel level, PortalChunkTerrain.Source source) {
        List<Structure> out = new ArrayList<>();
        if (level.getServer() == null) return out;
        PortalChunkSources.Resolved own = PortalChunkSources.resolve(level.getServer(), source, level.getSeed());
        if (own == null) return out;
        Set<Holder<Biome>> biomes = Set.copyOf(own.generator().getBiomeSource().possibleBiomes());
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (Structure structure : registry) {
            if (source.vanillaOnly() && !VanillaOnlySample.allows(registry.getKey(structure))) continue;
            if (admitsAny(structure, biomes)) out.add(structure);
        }
        return out;
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
                                                     BoundingBox window, PortalChunkTerrain.Source source) {
        boolean vanillaOnly = source.vanillaOnly();
        Set<Holder<Biome>> present = biomesIn(chunk, window);
        Set<Holder<Biome>> elsewhere = otherDimensionBiomes(level, source);
        if (elsewhere.isEmpty()) return List.of();
        List<Structure> foreign = new ArrayList<>();
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (Structure structure : registry) {
            if (vanillaOnly && !VanillaOnlySample.allows(registry.getKey(structure))) continue;
            if (admitsAny(structure, elsewhere) && !admitsAny(structure, present)) {
                foreign.add(structure);
            }
        }
        return foreign;
    }

    /**
     * Every biome the dimensions {@code own} was <b>not</b> sampled from can generate.
     *
     * <p>Keyed off the room's own dimension rather than {@code level}'s, and read through
     * {@link PortalChunkSources} rather than the live levels alone: an editor world is a superflat
     * overworld with no Nether or End, so every room there is hosted in the overworld and the other
     * two dimensions exist only as stand-in generators. Asking the live levels found nothing foreign
     * at all there, and Test the Carriage never showed what a real world's rooms get.</p>
     */
    private static Set<Holder<Biome>> otherDimensionBiomes(ServerLevel level,
                                                           PortalChunkTerrain.Source own) {
        Set<Holder<Biome>> out = new java.util.LinkedHashSet<>();
        if (level.getServer() == null) return out;
        for (PortalChunkTerrain.Source source : PortalChunkTerrain.Source.values()) {
            if (source.levelKey().equals(own.levelKey())) continue;
            PortalChunkSources.Resolved other =
                PortalChunkSources.resolve(level.getServer(), source, level.getSeed());
            if (other == null) continue;
            out.addAll(other.generator().getBiomeSource().possibleBiomes());
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
}
