package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.worldgen.ChuncksBand;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.OfflineChunkSampler;
import games.brennan.dungeontrain.worldgen.SecondLapOverworld;
import games.brennan.dungeontrain.worldgen.SpheresBand;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.density.NetherBandContext;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBandKind;
import games.brennan.dungeontrain.worldgen.legacy.LegacyBands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Where a {@link PortalRoomMode#CHUNK_DIMENSION} room's terrain comes from: one chunk of ordinary
 * world generation, sampled out of a real dimension's generator and sliced so its surface lands on
 * the corridor doors.
 *
 * <h2>Sampled, never loaded</h2>
 * <p>Nothing here touches a chunk in the world. A forced {@code getChunk(FULL, true)} on the server
 * tick is the shape of the Sable worldgen deadlock — the reason {@code PortalRoomTiler.chunksLoaded}
 * asks rather than forces — and a portal room is stamped from inside exactly that loop. So the
 * generator fills a throwaway {@link ProtoChunk} that belongs to nobody, the dimension's real
 * surface rules are run over that, and the carvers, one structure and the biome's features follow
 * in a region built over the same throwaway chunks ({@link PortalChunkFeatures}). No chunk is
 * generated, loaded, saved or kept, in this world or any other.</p>
 *
 * <p>It is the same trick {@code NetherCoreGeometry} already plays on the Nether's density router,
 * one level up: that samples a function, this samples the whole stack of them that turns noise into
 * grass. What is deliberately <b>not</b> sampled is anything needing a {@code WorldGenRegion} —
 * carvers, ores, trees, structures — because a region means chunks, and chunks mean loading.</p>
 *
 * <h2>Off the tick, then cached</h2>
 * <p>Sampling takes tens of milliseconds, so it runs on {@code Util.backgroundExecutor()} and the
 * caller gets {@code null} until it lands ({@link #slice}). A structure is re-stamped every time the
 * train drifts past {@code TWIN_MAX_DRIFT}, so the answer is cached per pair key: a pair's chunk is
 * sampled once and re-stamped from memory for the life of the world, which is also what stops the
 * ground moving under a player who is standing on it.</p>
 */
public final class PortalChunkTerrain {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Footprint of the sampled column — one chunk, on both horizontal axes. */
    public static final int SIZE = 16;

    /**
     * How tall the sampled column is — two chunk sections.
     *
     * <p>The room is one chunk of ground with a second chunk of sky stacked on it. The upper half is
     * mostly air, and that is the point: a hill that keeps climbing, a tree, or a structure with a
     * tower on it has somewhere to go, instead of being cut off at a ceiling nine blocks over a
     * player's head. It also takes the door-height clamp off the doorways —
     * {@link PortalRoomLayout#maxDoorHeightOffset} is the room's height less the corridor's, so at
     * sixteen every second room had a mouth pinned against the ceiling.</p>
     */
    public static final int HEIGHT = 32;

    /**
     * Room-local Y the middle column's surface is slid onto — how the column is cut, not where a
     * doorway goes.
     *
     * <p>Nine blocks of ground under it, and the rest of {@link #HEIGHT} above: a cross-section deep
     * enough to read as ground a player is standing on rather than a floor, and shallow enough that
     * the room is mostly the sky and the terrain in it.</p>
     *
     * <p>The doorways are fitted to whatever the ground actually turns out to be at each mouth
     * ({@link PortalChunkDoors}), which is a different row from this one wherever the chunk slopes —
     * and the variant's authored offset is only the fallback for a room stamped before its sample
     * lands.</p>
     */
    public static final int SURFACE_ROW = 9;

    /** Blocks of column filled below the anchor before the surface rules run, for their depth tests. */
    private static final int CONTEXT_BELOW = 48;

    /** Blocks filled above it, so an overhang above the slice still shapes what is inside it. */
    private static final int CONTEXT_ABOVE = 24;

    /** Headroom an anchor row needs above it to count as somewhere a player can stand. */
    private static final int ANCHOR_HEADROOM = 5;

    /** How far from the origin, in chunks, sample sites are scattered. */
    private static final int SAMPLE_SPREAD = 60_000;

    /**
     * How many sites a pair may try before it settles for the best one it saw.
     *
     * <p>Most rejections are cheap and most sites pass first time in the Overworld. The End is what
     * this number is really for: its outer islands are specks in a great deal of nothing, so a
     * chunk-dimension room out there walks through a good few empty sites before it finds land.</p>
     */
    private static final int SITE_ATTEMPTS = 24;

    /** Where the four corner probes sit inside the chunk, in blocks from each edge. */
    private static final int PROBE_INSET = 4;

    /** How many of the five probe columns must have ground for a site to be somewhere worth being. */
    private static final int PROBES_REQUIRED = 3;

    /** How far a probe's own ground may sit from the centre's and still count as the same ground. */
    private static final int PROBE_SPREAD = 8;

    /** Crude bound on the cache: a world with more portal pairs than this drops the oldest wholesale. */
    private static final int MAX_CACHE = 512;

    private static final int SITE_X_SALT = 12;
    private static final int SITE_Z_SALT = 13;
    private static final int SITE_RANGE_SALT = 14;

    /** Where a Better room asks which biome a site is — about where the ground in the Nether and End sits. */
    private static final int BIOME_PROBE_Y = 64;

    /** How far apart consecutive attempts' salts sit, so the X and Z streams never collide. */
    private static final int SALT_STRIDE = 977;

    /**
     * Which dimension's generation a chunk dimension is a slice of — one per authored sub-variant.
     *
     * <p>Named by the variant rather than rolled per pair, which is what makes the frequency of each
     * an author's decision: the three rooms sit in a group sidecar beside every other portal room,
     * and the weights file says how often each turns up. The sky is authored on the same variants,
     * so nothing here has to know what a Nether room is lit like.</p>
     */
    public enum Source {
        OVERWORLD(Level.OVERWORLD, "minecraft:stone", SecondLapOverworld.Stretch.VANILLA),
        /** The overworld as the overhauled-vanilla stretch dresses it — see {@link SecondLapOverworld}. */
        OVERWORLD_WWOO(Level.OVERWORLD, "minecraft:stone", SecondLapOverworld.Stretch.WWOO),
        /** The overworld as the added-biomes stretch dresses it — see {@link SecondLapOverworld}. */
        OVERWORLD_BOP(Level.OVERWORLD, "minecraft:stone", SecondLapOverworld.Stretch.BOP),
        /** The Nether as vanilla generates it — cut with {@link SampleGenerators}' vanilla-biome generator. */
        NETHER(Level.NETHER, "minecraft:netherrack", null),
        /** The Nether's BetterNether biomes — sites taken only where the live Nether places one. */
        NETHER_BETTER(Level.NETHER, "minecraft:netherrack", null, "betternether"),
        /** The End as vanilla generates it — cut with {@link SampleGenerators}' vanilla-island generator. */
        END(Level.END, "minecraft:end_stone", null),
        /** The End as BetterEnd generates it — sites taken only where the live End places one of its biomes. */
        END_BETTER(Level.END, "minecraft:end_stone", null, "betterend");

        private final ResourceKey<Level> levelKey;
        private final String groundId;
        private final SecondLapOverworld.Stretch stretch;
        private final String biomeNamespace;

        Source(ResourceKey<Level> levelKey, String groundId, SecondLapOverworld.Stretch stretch) {
            this(levelKey, groundId, stretch, null);
        }

        Source(ResourceKey<Level> levelKey, String groundId, SecondLapOverworld.Stretch stretch,
               String biomeNamespace) {
            this.levelKey = levelKey;
            this.groundId = groundId;
            this.stretch = stretch;
            this.biomeNamespace = biomeNamespace;
        }

        /**
         * The biome namespace a site's centre must be in for this room to take it, or {@code null}
         * when any biome will do — how a Better room keeps to its mod's biomes.
         */
        public String biomeNamespace() {
            return biomeNamespace;
        }

        public ResourceKey<Level> levelKey() {
            return levelKey;
        }

        /**
         * True for the vanilla Nether and End rooms, which place only {@code minecraft:} features and
         * structures. Their private generators keep the Better mods' biomes and terrain out, but those
         * mods also add features to the vanilla biomes and list them in their structures' biome tags —
         * see {@link games.brennan.dungeontrain.worldgen.VanillaOnlySample}.
         */
        public boolean vanillaOnly() {
            return this == NETHER || this == END;
        }

        /**
         * Which overworld stretch this room's site must sit in — and so which look its terrain wears —
         * or {@code null} for the Nether and End, which have no stretches.
         */
        public SecondLapOverworld.Stretch stretch() {
            return stretch;
        }

        /**
         * The solid block a doorway apron is floored with when the sample left air under it — this
         * dimension's own filler, so the patch reads as the ground it was cut into.
         */
        public BlockState ground() {
            return BuiltInRegistries.BLOCK
                .get(ResourceLocation.parse(groundId)).defaultBlockState();
        }

        /**
         * The dimension a portal room variant samples: {@link #NETHER}, {@link #NETHER_BETTER},
         * {@link #END}, {@link #END_BETTER}, {@link #OVERWORLD_WWOO} and {@link #OVERWORLD_BOP} for
         * the named sub-variants,
         * {@link #OVERWORLD} for the parent and for anything unrecognised.
         *
         * <p>Total rather than throwing, for the reason every other reader of authored text in this
         * package is: the name comes off disk, and a room whose sidecar was hand-edited to something
         * misspelt should stamp a field rather than fail the pair's stamp.</p>
         */
        public static Source of(String roomName) {
            if (roomName == null) return OVERWORLD;
            String key = roomName.trim().toLowerCase(Locale.ROOT);
            if (key.endsWith(NETHER_SUFFIX + BETTER_SUFFIX)) return NETHER_BETTER;
            if (key.endsWith(END_SUFFIX + BETTER_SUFFIX)) return END_BETTER;
            if (key.endsWith(NETHER_SUFFIX)) return NETHER;
            if (key.endsWith(END_SUFFIX)) return END;
            if (key.endsWith(WWOO_SUFFIX)) return OVERWORLD_WWOO;
            if (key.endsWith(BOP_SUFFIX)) return OVERWORLD_BOP;
            return OVERWORLD;
        }
    }

    /** The namespace a BoP room's site must have its biome from. */
    private static final String BOP_NAMESPACE = "biomesoplenty";

    /** What a Nether chunk-dimension variant's name ends with. */
    private static final String NETHER_SUFFIX = "_nether";

    /** What an End one's does. */
    private static final String END_SUFFIX = "_end";

    /** What follows a Nether or End variant's suffix to make it the Better one's. */
    private static final String BETTER_SUFFIX = "_better";

    /** What the overhauled-vanilla overworld variant's name ends with. */
    private static final String WWOO_SUFFIX = "_wwoo";

    /** What the added-biomes overworld variant's name ends with. */
    private static final String BOP_SUFFIX = "_bop";

    // Sampled cubes by pair key, and the keys currently being sampled on a worker. Both static, both
    // dropped when the server stops (#clear, called from PortalCarriageEvents.onServerStopped) and
    // whenever the world seed changes under them, for the reason every other pair-keyed map here is:
    // the next world's pair 12 is a different room in a different place.
    //
    // Each cube carries the room it was sampled for. A live pair's room never changes under its key,
    // but Test the Carriage files every room under the one PortalTestSession.PAIR_KEY — and without
    // the name, testing the Nether room after the Overworld one stamped the Overworld's ground into it.
    private static final Map<Integer, Cached> READY = new ConcurrentHashMap<>();
    private static final Set<Integer> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /**
     * Pairs whose last sample found nowhere worth standing in any of its sites.
     *
     * <p>Their room stamps as its plain template, as it always did — but a caller waiting on the
     * sample (Test the Carriage) needs to know it is not coming, rather than waiting forever for a
     * cube that no amount of asking again will produce. Keyed to the room that failed, so a test
     * switched to another room mid-sample is never told the previous one's failure. Cleared by the
     * next request.</p>
     */
    private static final Map<Integer, String> FAILED = new ConcurrentHashMap<>();

    /** A sampled cube, the room it was sampled for, and the roll it was sampled at. */
    private record Cached(String roomName, int roll, PortalChunkSlice slice) {}

    /**
     * How many times each pair's chunk has been re-rolled — Test the Carriage's reseed.
     *
     * <p>A roll moves the pair on to a fresh run of candidate sites, so a reseed lands on different
     * ground. Absent means zero, the sequence every pair walks in play, so nothing a player meets is
     * changed by it.</p>
     */
    private static final Map<Integer, Integer> ROLLS = new ConcurrentHashMap<>();

    /**
     * Pairs whose cube has since grown its structure and its features, and whose room is therefore a
     * pass behind what has been sampled for it.
     *
     * <p>Held until the room is actually rewritten rather than handed over once: a pair is normally
     * stamped a tick or two after its terrain lands, so the decoration is often ready before there is
     * a room to put it in. Drained by {@code PortalChunkDimension.applyPendingDecoration}.</p>
     */
    private static final Set<Integer> DECORATED = ConcurrentHashMap.newKeySet();

    /**
     * The one thread samples run on.
     *
     * <p>Not {@code Util.backgroundExecutor()}, and the reason is a deadlock that showed up as
     * "none of the portal carriages connect". Vanilla's {@code fillFromNoise} schedules its work on
     * that pool and hands back a future; a sample has to wait on that future; and a sample that is
     * itself occupying one of that pool's few threads is waiting on a pool that may have no thread
     * left to run the fill — every other thread being busy generating the train's own chunks, or
     * waiting exactly as this one is. Nothing ever completes, and no exception is ever thrown to say
     * so. On a thread of its own the sampler can wait on the pool without being part of it.</p>
     *
     * <p>One thread rather than several, so a train that plans a run of chunk dimensions at once
     * samples them in turn instead of contending with the worldgen pool for every core.</p>
     */
    private static final ExecutorService SAMPLER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "DungeonTrain-chunk-dimension-sampler");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile long cacheSeed = Long.MIN_VALUE;

    private PortalChunkTerrain() {}

    /**
     * This pair's sampled cube, or {@code null} when it is not ready yet — in which case sampling is
     * started on a worker and a later call answers.
     *
     * <p>Never blocks and never generates a chunk. A caller that gets {@code null} stamps the room's
     * own template and asks again next tick; see {@code PortalChunkDimension}.</p>
     */
    public static PortalChunkSlice slice(ServerLevel level, int pairKey, String roomName) {
        long seed = level.getSeed();
        if (seed != cacheSeed) {
            READY.clear();
            IN_FLIGHT.clear();
            FAILED.clear();
            cacheSeed = seed;
        }
        Cached ready = READY.get(pairKey);
        if (ready != null) {
            if (sameRoom(ready.roomName(), roomName) && ready.roll() == rollOf(pairKey)) {
                return ready.slice();
            }
            // A different room under the same key, or a re-roll — only the test rig does either. Its
            // cube is not the ground being asked for, so it goes, and that is sampled in its place.
            READY.remove(pairKey, ready);
        }
        request(level, pairKey, roomName);
        return null;
    }

    /**
     * Move this pair on to a fresh chunk: the next {@link #slice} samples a new site. Test the
     * Carriage's reseed — the terrain is a chunk dimension's contents, so a fresh roll is a fresh
     * chunk.
     */
    public static void reroll(int pairKey) {
        ROLLS.merge(pairKey, 1, Integer::sum);
        FAILED.remove(pairKey);
    }

    static int rollOf(int pairKey) {
        return ROLLS.getOrDefault(pairKey, 0);
    }

    /** Whether a cube sampled for {@code cachedRoom} is the ground for {@code roomName}. */
    static boolean sameRoom(String cachedRoom, String roomName) {
        return java.util.Objects.equals(cachedRoom, roomName);
    }

    /**
     * True when this pair's last sample of {@code roomName} came back with nowhere to stand in any
     * of its sites (or threw), and nothing has asked again since. Asking again ({@link #slice})
     * clears it and retries.
     */
    public static boolean failed(int pairKey, String roomName) {
        String failedRoom = FAILED.get(pairKey);
        return failedRoom != null && failedRoom.equals(java.util.Objects.toString(roomName, ""));
    }

    /**
     * Start sampling this pair's cube if nothing is holding it yet.
     *
     * <p>Called from {@code planStructure} as well as from the stamp, so the work is usually already
     * done by the time a player has walked far enough down the train to reach the carriage.</p>
     */
    public static void request(ServerLevel level, int pairKey, String roomName) {
        long seed = level.getSeed();
        MinecraftServer server = level.getServer();
        if (server == null) return;
        if (READY.containsKey(pairKey)) return;
        if (!IN_FLIGHT.add(pairKey)) return;
        FAILED.remove(pairKey);
        Source source = Source.of(roomName);
        int roll = rollOf(pairKey);
        SAMPLER.execute(() -> {
            try {
                // Two passes, and the split is what keeps a portal carriage crossable. The first is
                // the ground — noise, surface rules and the carvers — which the pair cannot be
                // planned without, because its doorways stand on it. The second is everything that
                // grows on that ground, which costs seconds and moves nothing, so the room is built
                // from the first and rewritten when the second lands.
                long startedAt = System.currentTimeMillis();
                Sample sample = sampleTerrain(server, source, seed, pairKey, roll);
                if (sample == null) {
                    FAILED.put(pairKey, java.util.Objects.toString(roomName, ""));
                    LOGGER.warn("[DungeonTrain] Chunk dimension pair {} ('{}', {}) found no ground in "
                        + "{} site(s); the room stamps as its plain template", pairKey, roomName, source,
                        SITE_ATTEMPTS);
                    return;
                }
                if (READY.size() >= MAX_CACHE) READY.clear();
                READY.put(pairKey, new Cached(roomName, roll, sample.read()));
                long ground = System.currentTimeMillis() - startedAt;

                long decoratingFrom = System.currentTimeMillis();
                PortalChunkFeatures.decorate(sample.generator(), sample.level(), sample.random(),
                    sample.chunk(), sample.workspace(), sample.window(), sample.level().getSeed(),
                    pairKey, sample.source().vanillaOnly());
                READY.put(pairKey, new Cached(roomName, roll, sample.read()));
                DECORATED.add(pairKey);
                // The first number is what a portal carriage waits out before it can cross at all,
                // so it is the one worth watching; the second is only how long the room takes to
                // grow afterwards.
                Cached decorated = READY.get(pairKey);
                // The biome too: it is what says whether a WWOO, BoP or Better room actually landed
                // in its mod's world generation or quietly came back vanilla.
                LOGGER.info("[DungeonTrain] Chunk dimension pair {} sampled from {} ({}) at {} in {}: "
                        + "ground in {} ms, decoration in {} ms, {} mob(s) generated with it",
                    pairKey, source, source.levelKey().location(), sample.pos(), biomeAt(sample), ground,
                    System.currentTimeMillis() - decoratingFrom,
                    decorated == null ? 0 : decorated.slice().occupants().size());
            } catch (Throwable t) {
                FAILED.put(pairKey, java.util.Objects.toString(roomName, ""));
                // A failed sample is a room that stamps as its plain template — never a crashed
                // worker, and never a pair that retries the same failure every tick.
                LOGGER.warn("[DungeonTrain] Chunk dimension sample failed for pair {} ({})",
                    pairKey, source, t);
            } finally {
                IN_FLIGHT.remove(pairKey);
            }
        });
    }

    /** The biome at the middle of a sample's surface row, by id — for the log. */
    private static String biomeAt(Sample sample) {
        try {
            return sample.generator().getBiomeSource().getNoiseBiome(
                    QuartPos.fromBlock(sample.pos().getMiddleBlockX()), QuartPos.fromBlock(sample.anchor()),
                    QuartPos.fromBlock(sample.pos().getMiddleBlockZ()), sample.random().sampler())
                .unwrapKey().map(k -> k.location().toString()).orElse("?");
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Drop every sampled cube — the next world's pair keys mean different rooms. */
    public static void clear() {
        SampleGenerators.clear();
        READY.clear();
        IN_FLIGHT.clear();
        FAILED.clear();
        DECORATED.clear();
        ROLLS.clear();
        PortalChunkSources.clear();
        cacheSeed = Long.MIN_VALUE;
    }

    /** This pair's cube if one has been sampled, without asking for one that has not. */
    static PortalChunkSlice peek(int pairKey) {
        Cached cached = READY.get(pairKey);
        return cached == null ? null : cached.slice();
    }

    /** The pairs whose rooms are a decoration pass behind their cube, as a snapshot. */
    static Set<Integer> decorated() {
        return Set.copyOf(DECORATED);
    }

    /** Say that {@code pairKey}'s room has been rewritten with its decorated cube. */
    static void decorationApplied(int pairKey) {
        DECORATED.remove(pairKey);
    }

    // ---- sampling ------------------------------------------------------------

    /**
     * One sample, mid-flight: the throwaway chunk and everything needed to read a cube out of it or
     * to run another pass of generation over it.
     */
    private record Sample(ServerLevel level, NoiseBasedChunkGenerator generator, RandomState random,
                          ProtoChunk chunk, OfflineChunkSampler.Workspace workspace, ChunkPos pos,
                          Source source, int anchor, int minY, int maxY, int probes) {

        /** The same sample cut around a different row — what carving the ground under it moves. */
        Sample at(int newAnchor) {
            return new Sample(level, generator, random, chunk, workspace, pos, source, newAnchor,
                minY, maxY, probes);
        }

        /** The cube as the chunk currently stands — called once per generation pass. */
        PortalChunkSlice read() {
            return readSlice(level, source, chunk, pos, anchor, minY, maxY);
        }

        /** The rows of the chunk the room will show, in world coordinates. */
        BoundingBox window() {
            int lo = anchor - SURFACE_ROW;
            return new BoundingBox(pos.getMinBlockX(), lo, pos.getMinBlockZ(),
                pos.getMaxBlockX(), lo + HEIGHT - 1, pos.getMaxBlockZ());
        }
    }

    /**
     * Sample one cube's ground: pick a site, pour the generator's columns into a throwaway chunk,
     * run the surface rules over it and cut its caves. Runs on a worker thread and touches nothing
     * but the generator, its random state and a {@link ProtoChunk} of its own.
     */
    private static Sample sampleTerrain(MinecraftServer server, Source source, long worldSeed,
                                        int pairKey, int roll) {
        // The source dimension's own generator — or, in a world that has none to sample (an editor
        // world is superflat with no Nether or End), the vanilla preset's. See PortalChunkSources.
        PortalChunkSources.Resolved resolved = PortalChunkSources.resolve(server, source, worldSeed);
        if (resolved == null) return null;
        ServerLevel level = resolved.host();
        // The vanilla Nether and End rooms are cut with a private generator that leaves the Better
        // mods out; every other room with the dimension's own. Only a live dimension has a live
        // generator to build that from — a stand-in preset is already vanilla's.
        NoiseBasedChunkGenerator noiseGenerator = resolved.fallback()
            ? resolved.generator()
            : SampleGenerators.forSource(level, source, resolved.generator());
        RandomState random = resolved.random();
        int minY = resolved.minY();
        int maxY = resolved.maxY();

        // Somewhere worth standing in, rather than the first place the hash pointed at. A site is
        // taken when most of its columns have standable ground at about one height: that one rule
        // turns away both of the samples a player does not want to walk into — the void, where
        // nothing is solid at all, and the open ocean, whose seabed has water on top of it rather
        // than air and so offers no row to stand on. Rivers, lakes, coastlines and cave floors all
        // still pass, because in each of those a player can stand on the ground and breathe.
        //
        // Each candidate is generated to be judged, rather than probed column by column first. A
        // whole chunk costs about what four of those probes did: a column asked for on its own runs
        // the entire noise router down the world's height, while a chunk gets vanilla's interpolated
        // cell grid — which is the reason the game generates chunks and not columns, and the reason
        // a portal carriage used to wait ten seconds for its room.
        Sample best = null;
        int tried = 0;
        SitePlan plan = SitePlan.of(level, source, noiseGenerator.getBiomeSource(), random.sampler());
        for (int attempt = 0; attempt < SITE_ATTEMPTS; attempt++) {
            // A re-roll walks on past every site the earlier rolls could have tried.
            ChunkPos site = plan.site(worldSeed, pairKey, roll * SITE_ATTEMPTS + attempt);
            // Free, and it saves generating a chunk to find out: DT's own bands void whole stretches
            // of the overworld, and a sample that lands in one comes back empty however long it is
            // generated for. Asked before the work rather than after it — this used to be most of
            // what a candidate cost. The stretch test is free too, and is what keeps the plain
            // overworld room plain: a site in a band, a legacy era or a modded stretch would wear
            // that look under the train wherever the room turned up.
            //
            // The stretch test still applies to a stand-in preset: an editor world has no bands to
            // void a site, but its configured cycle still says where WWOO and BoP grow, and a WWOO
            // or BoP room sampled outside its stretch comes back vanilla.
            if ((!resolved.fallback() && voidedByBand(level, site)) || !plan.accepts(site)) continue;
            tried++;
            Sample candidate = groundAt(level, noiseGenerator, random, site, source, minY, maxY);
            if (candidate == null) continue;
            if (candidate.probes() >= PROBES_REQUIRED) {
                best = candidate;
                break;
            }
            if (best == null || candidate.probes() > best.probes()) best = candidate;
        }
        if (best == null) return null;
        LOGGER.debug("[DungeonTrain] Chunk dimension pair {} ({}) took {} generated site(s); site x={} z={}",
            pairKey, source, tried, best.pos().getMinBlockX(), best.pos().getMinBlockZ());

        // Only the site that was kept pays for its caves.
        PortalChunkFeatures.carve(noiseGenerator, level, random, best.chunk(), best.workspace(),
            level.getSeed(), pairKey);
        // Re-read after carving: a cavern opened under the middle column moves the row this cube is
        // cut around, and the doorways are fitted to whatever it ends up being.
        int anchor = standableRow(best.chunk(), SIZE / 2, SIZE / 2, minY, maxY);
        return anchor == NO_GROUND ? best : best.at(anchor);
    }

    /**
     * Generate one candidate site's ground — terrain and surface rules — and judge it.
     *
     * <p>Null when the middle column has nowhere to stand at all: open ocean, the End's void, or
     * solid rock to the ceiling. Otherwise the sample carries how many of its five probe columns
     * agree on roughly one ground height, which is what {@link #sampleTerrain} ranks sites by.</p>
     */
    private static Sample groundAt(ServerLevel level, NoiseBasedChunkGenerator generator,
                                   RandomState random, ChunkPos pos, Source source,
                                   int minY, int maxY) {
        // Biomes filled and the chunk marked SURFACE (vanilla refuses biome questions below BIOMES),
        // then vanilla's own fill through a structure manager bound to the sample's own region, so
        // nothing reaches into the world for a structure start — see OfflineChunkSampler.
        ProtoChunk chunk = OfflineChunkSampler.blankSample(level, generator, random, pos);
        OfflineChunkSampler.Workspace workspace =
            OfflineChunkSampler.workspaceFor(level, generator, random, chunk);
        ProtoChunk ground = OfflineChunkSampler.fillGround(level, generator, random, chunk, workspace);
        if (ground == null) return null;

        int anchor = standableRow(ground, SIZE / 2, SIZE / 2, minY, maxY);
        if (anchor == NO_GROUND) return null;

        int agreeing = 1;
        int[][] corners = {
            {PROBE_INSET, PROBE_INSET},
            {SIZE - 1 - PROBE_INSET, PROBE_INSET},
            {PROBE_INSET, SIZE - 1 - PROBE_INSET},
            {SIZE - 1 - PROBE_INSET, SIZE - 1 - PROBE_INSET},
        };
        for (int[] corner : corners) {
            int row = standableRow(ground, corner[0], corner[1], minY, maxY);
            if (row != NO_GROUND && Math.abs(row - anchor) <= PROBE_SPREAD) agreeing++;
        }
        return new Sample(level, generator, random, ground, workspace, pos, source, anchor, minY,
            maxY, agreeing);
    }

    /** Copy the column around the anchor out of the sampled chunk, room-local. */
    private static PortalChunkSlice readSlice(ServerLevel level, Source source, ProtoChunk chunk,
                                              ChunkPos pos, int anchor, int minY, int maxY) {
        BlockState[] states = new BlockState[SIZE * SIZE * HEIGHT];
        BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < HEIGHT; y++) {
            int worldY = anchor + (y - SURFACE_ROW);
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    BlockState state = air;
                    if (worldY >= minY && worldY <= maxY) {
                        state = chunk.getBlockState(
                            cursor.set(pos.getMinBlockX() + x, worldY, pos.getMinBlockZ() + z));
                    }
                    states[(y * SIZE + z) * SIZE + x] = state;
                }
            }
        }
        return new PortalChunkSlice(source, SIZE, HEIGHT, states,
            blockEntitiesIn(level, chunk, pos, anchor), occupantsIn(chunk, pos, anchor), biomesIn(chunk, pos, anchor, minY, maxY));
    }

    /**
     * The sampled chunk's biome at each quart of the window, bottom row first — sampled at the middle
     * of each quart, and held to the dimension's own height where the cut runs past it.
     */
    @SuppressWarnings("unchecked")
    private static Holder<Biome>[] biomesIn(ProtoChunk chunk, ChunkPos pos, int anchor, int minY,
                                            int maxY) {
        int qw = PortalChunkSlice.quarts(SIZE);
        int qh = PortalChunkSlice.quarts(HEIGHT);
        Holder<Biome>[] out = new Holder[qw * qh * qw];
        for (int qy = 0; qy < qh; qy++) {
            int worldY = Math.max(minY, Math.min(maxY, anchor - SURFACE_ROW + qy * 4 + 2));
            for (int qz = 0; qz < qw; qz++) {
                for (int qx = 0; qx < qw; qx++) {
                    out[(qy * qw + qz) * qw + qx] = chunk.getNoiseBiome(
                        QuartPos.fromBlock(pos.getMinBlockX() + qx * 4),
                        QuartPos.fromBlock(worldY),
                        QuartPos.fromBlock(pos.getMinBlockZ() + qz * 4));
                }
            }
        }
        return out;
    }

    /**
     * The block entities the sample left inside the window, as saved NBT, keyed by the same index
     * the states are.
     *
     * <p>Both halves of the chunk's bookkeeping, because a freshly generated structure's chests are
     * in the <b>pending</b> one: they are NBT that has never been promoted to a live block entity,
     * and asking only for the live map would hand back nothing at all. See
     * {@code SilentBlockOps.evictBlockEntity} for the same distinction from the other direction.</p>
     */
    private static Map<Integer, CompoundTag> blockEntitiesIn(ServerLevel level, ProtoChunk chunk,
                                                             ChunkPos pos, int anchor) {
        Map<Integer, CompoundTag> out = new java.util.HashMap<>();
        chunk.getBlockEntityNbts().forEach((at, nbt) -> {
            if (OfflineChunkSampler.isPlaceholderBlockEntity(nbt)) return;   // data-less; the live block makes its own
            Integer index = windowIndex(at, pos, anchor);
            if (index != null) out.put(index, nbt.copy());
        });
        chunk.getBlockEntities().forEach((at, blockEntity) -> {
            Integer index = windowIndex(at, pos, anchor);
            if (index != null) {
                out.put(index, blockEntity.saveWithFullMetadata(level.registryAccess()));
            }
        });
        return out;
    }

    /**
     * The entities the sample was generated with, in room-local coordinates.
     *
     * <p>The biome's own animals and whatever the structure was placed with — both arrive as NBT in
     * the throwaway chunk's entity list, because a generating chunk holds its mobs the way a saved
     * one does rather than as live entities in a world.</p>
     */
    private static java.util.List<PortalChunkSlice.Occupant> occupantsIn(ProtoChunk chunk,
                                                                        ChunkPos pos, int anchor) {
        java.util.List<PortalChunkSlice.Occupant> out = new java.util.ArrayList<>();
        for (CompoundTag nbt : chunk.getEntities()) {
            net.minecraft.nbt.ListTag position = nbt.getList("Pos", net.minecraft.nbt.Tag.TAG_DOUBLE);
            if (position.size() != 3) continue;
            double x = position.getDouble(0) - pos.getMinBlockX();
            double y = position.getDouble(1) - anchor + SURFACE_ROW;
            double z = position.getDouble(2) - pos.getMinBlockZ();
            if (x < 0 || z < 0 || x >= SIZE || z >= SIZE || y < 0 || y >= HEIGHT) continue;
            CompoundTag copy = nbt.copy();
            // The sample's own identity goes no further: spawning a mob under the UUID it was
            // generated with would collide with itself the second time a cube is used.
            copy.remove("UUID");
            out.add(new PortalChunkSlice.Occupant(copy, x, y, z));
        }
        return out;
    }

    /** Where a world position falls in the cut window, or null when it falls outside it. */
    private static Integer windowIndex(BlockPos at, ChunkPos pos, int anchor) {
        int x = at.getX() - pos.getMinBlockX();
        int z = at.getZ() - pos.getMinBlockZ();
        int y = at.getY() - anchor + SURFACE_ROW;
        if (x < 0 || z < 0 || y < 0 || x >= SIZE || z >= SIZE || y >= HEIGHT) return null;
        return (y * SIZE + z) * SIZE + x;
    }

    /** What {@link #standableRow} answers for a column with nowhere to stand. */
    private static final int NO_GROUND = Integer.MIN_VALUE;

    /**
     * The row a player's feet would go on in this column: the highest air row with <b>solid</b>
     * ground beneath it and {@link #ANCHOR_HEADROOM} of clearance above.
     *
     * <p>Not {@code getBaseHeight}, which is the open sky above the terrain — right in the Overworld
     * and wrong in the Nether, where it lands on the bedrock roof and would slice the ceiling
     * instead of a cavern floor.</p>
     *
     * <p><b>Solid means solid, not merely not-air</b>, and that one word is what keeps oceans out.
     * A sea column has a seabed with seventeen blocks of water on it: treat water as ground and the
     * row above the waves passes every other test here, and the room comes back a cube of sea. With
     * fluids refused there is no standable row in open water at all, the site is turned down, and
     * the pair tries somewhere else — while a lake or a river, which is ground with a puddle on it,
     * still passes on the columns either side.</p>
     */
    private static int standableRow(ChunkAccess chunk, int localX, int localZ, int minY, int maxY) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int worldX = chunk.getPos().getMinBlockX() + localX;
        int worldZ = chunk.getPos().getMinBlockZ() + localZ;
        for (int y = maxY - ANCHOR_HEADROOM; y > minY; y--) {
            if (!chunk.getBlockState(cursor.set(worldX, y - 1, worldZ)).blocksMotion()) continue;
            boolean clear = true;
            for (int h = 0; h < ANCHOR_HEADROOM; h++) {
                if (!chunk.getBlockState(cursor.set(worldX, y + h, worldZ)).isAir()) {
                    clear = false;
                    break;
                }
            }
            if (clear) return y;
        }
        return NO_GROUND;
    }

    /**
     * True when Dungeon Train's own world generation would hand this site back empty.
     *
     * <p>The overworld a chunk dimension samples is the one the train is running through, and that
     * one has bands in it: stretches where {@code NoiseBasedChunkGeneratorMixin} cancels the fill
     * outright ({@link ChuncksBand#isVoidChunk}) and stretches the disintegration band has eaten
     * ({@link DisintegrationBand#isChunkFullyEroded}). A site in either generates as air, fails the
     * ground test, and costs a full chunk of generation to say so. Both questions are pure functions
     * of the chunk's coordinates, so asking them first is free.</p>
     *
     * <p>Only the overworld has them, which is why a Nether sample was always fast and an overworld
     * one was not.</p>
     */
    private static boolean voidedByBand(ServerLevel level, ChunkPos site) {
        if (!level.dimension().equals(Level.OVERWORLD)) return false;
        try {
            return ChuncksBand.isVoidChunk(level, site.getMinBlockX(), site.getMinBlockZ())
                || SpheresBand.isVoidChunk(level, site.getMinBlockX(), site.getMinBlockZ())
                // Void-below legacy bands (Indev floating, Skylands): islands over open void — never solid
                // ground for a room.
                || isVoidBelowLegacy(LegacyBands.kindOfChunk(level, site.x, site.z))
                || DisintegrationBand.isChunkFullyEroded(level, site.getMinBlockX());
        } catch (Throwable t) {
            // The bands are the train's business, not the sample's: if either cannot answer, the
            // site is judged the ordinary way, by generating it.
            return false;
        }
    }

    private static boolean isVoidBelowLegacy(LegacyBandKind kind) {
        return kind != null && kind.voidBelow();
    }

    /**
     * How a pair's candidate sites are chosen and judged for one source — the stretch it wants, the
     * cycle that says where stretches are, and (for a modded stretch) the chunk-X ranges inside it.
     *
     * <p>The plain overworld room scatters its sites as it always did and turns away any outside
     * vanilla overworld. A modded room is handed sites inside its own stretch, because a scattered
     * one would almost never land there; in a world with no such stretch (its band switched off) it
     * falls back to the plain room's sites rather than to an empty room.</p>
     */
    record SitePlan(WorldGenCycle cycle, SecondLapOverworld.Stretch stretch, List<int[]> ranges,
                    String biomeNamespace, BiomeSource biomes, Climate.Sampler sampler) {

        SitePlan(WorldGenCycle cycle, SecondLapOverworld.Stretch stretch, List<int[]> ranges) {
            this(cycle, stretch, ranges, null, null, null);
        }

        static SitePlan of(ServerLevel level, Source source) {
            return of(level, source, level.getChunkSource().getGenerator().getBiomeSource(),
                level.getChunkSource().randomState().sampler());
        }

        /**
         * The plan for sampling {@code source} out of {@code biomes} — the generator actually being
         * sampled, which in a world with nothing to sample is a stand-in rather than the level's own.
         */
        static SitePlan of(ServerLevel level, Source source, BiomeSource biomes, Climate.Sampler sampler) {
            if (source.biomeNamespace() != null) {
                // A Better room: any site will do, as long as the dimension puts one of its mod's
                // biomes there.
                return new SitePlan(null, null, List.of(), source.biomeNamespace(), biomes, sampler);
            }
            if (source.stretch() == null || !level.dimension().equals(Level.OVERWORLD)) {
                return new SitePlan(null, null, List.of());
            }
            WorldGenCycle cycle = liveCycle();
            if (source.stretch() == SecondLapOverworld.Stretch.VANILLA) {
                return new SitePlan(cycle, SecondLapOverworld.Stretch.VANILLA, List.of());
            }
            List<int[]> ranges = StretchSites.chunkRanges(cycle, source.stretch(), (long) SAMPLE_SPREAD * SIZE);
            if (ranges.isEmpty()) {
                LOGGER.debug("[DungeonTrain] No {} stretch in this world; {} samples plain overworld",
                    source.stretch(), source);
                return new SitePlan(cycle, SecondLapOverworld.Stretch.VANILLA, List.of());
            }
            if (source.stretch() == SecondLapOverworld.Stretch.BOP) {
                // In the BoP stretch and on a BoP biome. BoP leaves rivers and shores to vanilla, and
                // a flat river valley is exactly the level, standable ground a site is judged on — so
                // without asking for the biome too, a BoP room kept landing on a vanilla river.
                return new SitePlan(cycle, source.stretch(), ranges, BOP_NAMESPACE, biomes, sampler);
            }
            return new SitePlan(cycle, source.stretch(), ranges);
        }

        ChunkPos site(long worldSeed, int pairKey, int attempt) {
            ChunkPos scattered = siteFor(worldSeed, pairKey, attempt);
            if (ranges.isEmpty()) return scattered;
            int chunkX = StretchSites.chunkXIn(ranges,
                hash01(worldSeed, pairKey, SITE_RANGE_SALT + attempt * SALT_STRIDE),
                hash01(worldSeed, pairKey, SITE_X_SALT + attempt * SALT_STRIDE));
            return new ChunkPos(chunkX, scattered.z);
        }

        boolean accepts(ChunkPos site) {
            if (biomeNamespace != null && !biomeNamespace.equals(biomeNamespaceAt(biomes, sampler, site))) {
                return false;
            }
            if (stretch == null) return true;
            try {
                return StretchSites.matches(cycle, stretch, site.getMinBlockX());
            } catch (Throwable t) {
                // Like voidedByBand: the cycle is the train's business, and a site it cannot place is
                // judged the ordinary way rather than lost.
                return true;
            }
        }
    }

    /**
     * The namespace of the biome {@code biomes} places at the middle of {@code site}, at
     * {@link #BIOME_PROBE_Y} — one noise lookup, no generation. {@code null} if the source cannot say.
     */
    public static String biomeNamespaceAt(BiomeSource biomes, Climate.Sampler sampler, ChunkPos site) {
        try {
            Holder<Biome> biome = biomes.getNoiseBiome(QuartPos.fromBlock(site.getMiddleBlockX()),
                QuartPos.fromBlock(BIOME_PROBE_Y), QuartPos.fromBlock(site.getMiddleBlockZ()), sampler);
            return biome.unwrapKey().map(k -> k.location().getNamespace()).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The cycle live world generation is using — the band context's, else the config's. */
    static WorldGenCycle liveCycle() {
        NetherBandContext ctx = NetherBandContext.current();
        if (ctx != null && ctx.cycle() != null) return ctx.cycle();
        return WorldGenCycle.fromConfig();
    }

    /**
     * The chunk a pair would sample for {@code source}, judged by site alone — no generation, so it
     * is the first candidate that passes the free tests, not necessarily the one a room ends up
     * showing. For {@code /dungeontrain debug portal-sites}. {@code null} when every attempt failed.
     */
    public static ChunkPos firstAcceptedSite(ServerLevel level, Source source, long worldSeed, int pairKey) {
        SitePlan plan = SitePlan.of(level, source);
        for (int attempt = 0; attempt < SITE_ATTEMPTS; attempt++) {
            ChunkPos site = plan.site(worldSeed, pairKey, attempt);
            if (!voidedByBand(level, site) && plan.accepts(site)) return site;
        }
        return null;
    }

    /** The chunk the scattered sites start from — where a pair's search began before stretches counted. */
    public static ChunkPos firstScatteredSite(long worldSeed, int pairKey) {
        return siteFor(worldSeed, pairKey, 0);
    }

    /**
     * A pair's {@code attempt}-th candidate site — scattered far from the train, and stable in the
     * seed, the key and the attempt, so a pair walks the same sequence every time it is sampled.
     */
    private static ChunkPos siteFor(long worldSeed, int pairKey, int attempt) {
        int chunkX = (int) ((hash01(worldSeed, pairKey, SITE_X_SALT + attempt * SALT_STRIDE) - 0.5)
            * 2 * SAMPLE_SPREAD);
        int chunkZ = (int) ((hash01(worldSeed, pairKey, SITE_Z_SALT + attempt * SALT_STRIDE) - 0.5)
            * 2 * SAMPLE_SPREAD);
        return new ChunkPos(chunkX, chunkZ);
    }

    /**
     * A uniform {@code [0,1)} value per {@code (seed, key, salt)} — the splitmix64 finaliser
     * {@code ChuncksBand.hash01} uses, and for the same reason: the roll has to be pure, cheap and
     * identical on every machine that asks.
     */
    private static double hash01(long seed, int key, int salt) {
        long h = seed * 0x9E3779B97F4A7C15L + salt * 0xD1B54A32D192ED03L;
        h ^= (long) key * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return (h >>> 11) * 0x1.0p-53;
    }
}
