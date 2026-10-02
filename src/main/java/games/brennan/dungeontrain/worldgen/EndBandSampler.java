package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.EndBandConfig;
import games.brennan.dungeontrain.config.SpheresProgressionConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.ticks.ProtoChunkTicks;
import org.slf4j.Logger;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates the terrain of a <b>BetterEnd</b> or <b>Biomes O' Plenty</b> End-band pass
 * ({@link WorldGenCycle#endStyleOfPass}) one display chunk at a time, off the server thread.
 *
 * <p>Each job runs a real End generator — the live End's, which BetterEnd: New Dawn drives with its own
 * biomes, or for a BoP pass {@link BopEnd}'s vanilla-island End with BoP's End biomes — into a
 * throwaway chunk in the outer End ({@link EndBandStyle#endChunkOffsetX}): noise, surface rules,
 * carvers, then the biome's full decoration (BetterEnd's trees, plants, crystals, lakes). The End's
 * island Y band is copied out shifted onto track level ({@link EndBandStyle#displayY}), with any
 * block-entity NBT re-keyed to display positions. Finished {@link Result}s wait in a queue for
 * {@code WorldEndBandEvents} to write into the live chunk on the server thread.</p>
 *
 * <p>Jobs are taken <b>nearest player first</b> ({@link EndBandJobQueue}), not in arrival order: the
 * prefetch strip keeps queuing chunks beyond the view, and under load the chunks beside the player used to
 * wait behind them as squares of void in the islands.</p>
 *
 * <p>Runs on the {@link SamplerPool} it shares with {@link ForeignSphereSampler}. Each request queues its
 * job here and posts one token to the pool; a token runs whichever job is nearest a player <em>when it
 * starts</em>, or nothing if the queue has emptied (job replaced, dropped or cleared) — so the order is
 * decided at run time, and every job has a token.</p>
 */
public final class EndBandSampler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /**
     * One sampled display chunk: {@code states} is {@code 16 × 16 × height} display-space blocks
     * starting at world Y {@code minY}; {@code blockEntities} maps packed display {@link BlockPos}
     * longs to their NBT.
     */
    public record Result(ChunkPos pos, int minY, int height, BlockState[] states,
                         Map<Long, CompoundTag> blockEntities, Map<Long, EndBandSpill> spill) {

        /** This sample with {@code spill}: what its features wrote into each neighbouring display chunk, by chunk key. */
        public Result withSpill(Map<Long, EndBandSpill> spill) {
            return new Result(pos, minY, height, states, blockEntities, spill);
        }

        /** The sampled display-space block at chunk-local {@code (dx, dz)} and world {@code y}. */
        public BlockState stateAt(int dx, int y, int dz) {
            int ly = y - minY;
            if (ly < 0 || ly >= height) return AIR;
            BlockState s = states[index(dx, ly, dz)];
            return s == null ? AIR : s;
        }

        static int index(int dx, int ly, int dz) {
            return (ly * 16 + dz) * 16 + dx;
        }
    }

    private static final Set<Long> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<Result> READY = new ConcurrentLinkedQueue<>();
    /** Bumped on server stop so a job still running for the old server drops its result. */
    private static final AtomicInteger EPOCH = new AtomicInteger();
    /** Waiting jobs, nearest player first; taken by the tokens {@link #request} posts to {@link SamplerPool}. */
    private static final EndBandJobQueue<Job> QUEUE = new EndBandJobQueue<>();
    /** Undecorated End ground shared between samples under {@code endBandFeatureSpill} ({@link #sampleWithSpill}). */
    private static final EndBandGroundCache<ProtoChunk> GROUND = new EndBandGroundCache<>(EndBandGroundCache.DEFAULT_CAP);
    /** Chunks from the nearest player beyond which a waiting job is dropped (view + prefetch strip + slack). */
    private static volatile int keepRadius = Integer.MAX_VALUE;

    /** One queued sample: the chunk it is for, and the work. */
    private record Job(long key, Runnable work) {}

    /**
     * How a sample's ground is filled: {@link OfflineChunkSampler#fillGround} joins the noise future from a
     * sampler thread; {@link OfflineChunkSampler#fillGroundInline} runs the noise on the calling worldgen
     * worker, which must never wait on the pool it is part of.
     */
    @FunctionalInterface
    private interface GroundFill {
        ProtoChunk fill(ServerLevel end, NoiseBasedChunkGenerator noise, RandomState random, ProtoChunk chunk,
                        OfflineChunkSampler.Workspace workspace);
    }

    private EndBandSampler() {}

    /**
     * Sample display chunk {@code pos}'s End terrain <b>now, on this thread</b> — for the worldgen path
     * ({@code EndBandInlineTerrain}), where the display chunk is still being generated and the terrain
     * goes straight into it. Never touches the queue; timed into {@code END_BAND_SAMPLE} like a queued job.
     * Returns {@code null} when the End can't be sampled (the caller flags the chunk for the background path).
     */
    public static Result sampleNow(MinecraftServer server, ChunkPos pos, long passIndex, int bedY,
                                   int displayMinY, int displayMaxY) {
        long t0 = System.nanoTime();
        try {
            return sample(server, pos, passIndex, bedY, displayMinY, displayMaxY, OfflineChunkSampler::fillGroundInline);
        } finally {
            GenProfiler.addNanos(GenProfiler.Bucket.END_BAND_SAMPLE, System.nanoTime() - t0);
        }
    }

    /**
     * True if this server's End can be sampled — it exists and runs a noise generator. When false every
     * pass falls back to the vanilla stamp, so a broken or replaced End generator never leaves the band empty.
     */
    public static boolean available(MinecraftServer server) {
        if (server == null) return false;
        ServerLevel end = server.getLevel(Level.END);
        return end != null && end.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator;
    }

    /**
     * True if an End-band pass of look {@code style} ({@link WorldGenCycle#endStyleOfPass}) gets sampled
     * terrain on this server — BetterEnd from the live End, BoP from {@link BopEnd} — the single gate
     * {@code DisintegrationFeature}, {@code BandEndCityStructure} and {@code WorldEndBandEvents} share, so
     * a pass is either fully vanilla-stamped or fully sampled, never both or neither.
     */
    public static boolean appliesTo(MinecraftServer server, CycleLayout.Style style) {
        if (style == CycleLayout.Style.BETTER) return available(server);
        if (style == CycleLayout.Style.BOP) return available(server) && BopEnd.get(server) != null;
        return false;
    }

    /**
     * Start sampling the End chunk that display chunk {@code pos} copies on pass {@code passIndex},
     * unless that job is already queued, running, or finished and waiting. Never blocks.
     */
    public static void request(ServerLevel overworld, ChunkPos pos, long passIndex, int bedY) {
        if (!IN_FLIGHT.add(pos.toLong())) return;
        MinecraftServer server = overworld.getServer();
        int displayMinY = overworld.getMinBuildHeight();
        int displayMaxY = overworld.getMaxBuildHeight();
        int epoch = EPOCH.get();
        QUEUE.add(pos.toLong(), pos.x, pos.z, new Job(pos.toLong(), () -> {
            long t0 = System.nanoTime();
            try {
                Result result = sample(server, pos, passIndex, bedY, displayMinY, displayMaxY, OfflineChunkSampler::fillGround);
                if (result != null && epoch == EPOCH.get()) READY.add(result);
                else IN_FLIGHT.remove(pos.toLong());
            } catch (Throwable t) {
                IN_FLIGHT.remove(pos.toLong());
                LOGGER.warn("[DungeonTrain] End-band sample failed for chunk {} (pass {})", pos, passIndex, t);
            }
            GenProfiler.addNanos(GenProfiler.Bucket.END_BAND_SAMPLE, System.nanoTime() - t0);
        }));
        SamplerPool.execute(EndBandSampler::runNearest);
    }

    /**
     * The players the queue is ordered by, and how far (in chunks) from the nearest of them a waiting job
     * may be before it is dropped. Server thread, every tick.
     */
    public static void updatePlayers(EndBandJobQueue.Players players, int dropBeyondChunks) {
        keepRadius = dropBeyondChunks;
        QUEUE.setPlayers(players);
    }

    /**
     * Move every finished sample into {@code out}. Each stays in flight until {@link #done} — so a chunk
     * that loads while its sample waits to be written doesn't ask for a second one. Server thread.
     */
    public static void drainReady(List<Result> out) {
        Result r;
        while ((r = READY.poll()) != null) out.add(r);
    }

    /** The sample for {@code pos} has been written (or given up on): it may be requested again. */
    public static void done(ChunkPos pos) {
        IN_FLIGHT.remove(pos.toLong());
    }

    /** Drop every queued and finished job (server stopping / world change). */
    public static void clear() {
        EPOCH.incrementAndGet();
        QUEUE.clear(job -> { });
        QUEUE.setPlayers(EndBandJobQueue.Players.NONE);
        keepRadius = Integer.MAX_VALUE;
        READY.clear();
        IN_FLIGHT.clear();
        GROUND.clear();
    }

    /** One token's work: run the waiting job nearest a player, if any. A dropped job frees its chunk to be requested again. Sampler thread. */
    private static void runNearest() {
        try {
            Job job = QUEUE.poll(keepRadius, dropped -> IN_FLIGHT.remove(dropped.key()));
            if (job != null) job.work().run();
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] End-band sampler job threw", t);
        }
    }

    /** Generate the End chunk behind display chunk {@code pos} and copy it out. Sampler thread, or a worldgen worker with the inline fill. */
    private static Result sample(MinecraftServer server, ChunkPos pos, long passIndex, int bedY,
                                 int displayMinY, int displayMaxY, GroundFill fill) {
        ServerLevel end = server.getLevel(Level.END);
        if (end == null) return null;
        ChunkGenerator generator = end.getChunkSource().getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator live)) return null;
        NoiseBasedChunkGenerator noise = live;
        boolean bop = WorldGenCycle.fromConfig().endStyleOfPass(passIndex) == CycleLayout.Style.BOP;
        if (bop) {
            BopEnd.Built built = BopEnd.get(server);
            if (built == null) return null;
            noise = built.generator();
        }
        // The live End's noise: the BoP generator's settings are a copy of the same End settings.
        RandomState random = end.getChunkSource().randomState();

        ChunkPos endPos = new ChunkPos(pos.x + EndBandStyle.endChunkOffsetX(passIndex), pos.z);
        if (EndBandConfig.featureSpill()) {
            return sampleWithSpill(end, noise, random, bop, pos, endPos, passIndex, bedY, displayMinY, displayMaxY, fill);
        }
        ProtoChunk chunk = OfflineChunkSampler.blankSample(end, noise, random, endPos);
        OfflineChunkSampler.Workspace workspace = OfflineChunkSampler.workspaceFor(end, noise, random, chunk);
        ProtoChunk ground = fill.fill(end, noise, random, chunk, workspace);
        if (ground == null) return null;
        try {
            OfflineChunkSampler.carve(noise, random, ground, workspace, end.getSeed());
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] End-band carvers failed at {} — keeping uncarved terrain", endPos, t);
        }
        try {
            // A BoP pass keeps vanilla + BoP features only: BetterEnd injects its ores into the vanilla
            // End biomes, which the BoP End shares.
            if (bop) OfflineChunkSampler.decorate(noise, workspace, ground, true, BopEnd.NAMESPACE);
            else OfflineChunkSampler.decorate(noise, workspace, ground);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] End-band decoration failed at {} — keeping bare terrain", endPos, t);
        }
        return copyOut(end, ground, pos, endPos, bedY, displayMinY, displayMaxY);
    }

    /**
     * {@link #sample} under {@code endBandFeatureSpill}: the chunk is decorated among <b>copies of its real
     * neighbours' ground</b> ({@link #GROUND}), the way vanilla decorates a chunk beside its generated
     * neighbours, and whatever its features wrote into them comes back as {@link Result#spill} for
     * {@code WorldEndBandEvents} to write into those display chunks. The cached ground is never decorated
     * itself — every sample works on copies — so a chunk reads the same neighbours whichever thread finishes
     * first. A neighbour whose ground fails to generate is left blank, as before.
     */
    private static Result sampleWithSpill(ServerLevel end, NoiseBasedChunkGenerator noise, RandomState random,
                                          boolean bop, ChunkPos pos, ChunkPos endPos, long passIndex, int bedY,
                                          int displayMinY, int displayMaxY, GroundFill fill) {
        CycleLayout.Style style = bop ? CycleLayout.Style.BOP : CycleLayout.Style.BETTER;
        ProtoChunk cached = cachedGround(end, noise, random, style, endPos, fill);
        if (cached == null) return null;
        ProtoChunk centre = copyGround(end, cached);
        Map<ChunkPos, ProtoChunk> originals = new HashMap<>(8);
        Map<ChunkPos, ProtoChunk> ring = new HashMap<>(8);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                ChunkPos n = new ChunkPos(endPos.x + dx, endPos.z + dz);
                ProtoChunk g = cachedGround(end, noise, random, style, n, fill);
                if (g == null) continue;
                originals.put(n, g);
                ring.put(n, copyGround(end, g));
            }
        }
        OfflineChunkSampler.Workspace workspace = OfflineChunkSampler.workspaceFor(end, noise, random, centre, ring::get);
        try {
            if (bop) OfflineChunkSampler.decorate(noise, workspace, centre, true, BopEnd.NAMESPACE);
            else OfflineChunkSampler.decorate(noise, workspace, centre);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] End-band decoration failed at {} — keeping bare terrain", endPos, t);
        }
        Result own = copyOut(end, centre, pos, endPos, bedY, displayMinY, displayMaxY);
        if (own == null) return null;
        int chunkOffsetX = EndBandStyle.endChunkOffsetX(passIndex);
        int shiftX = pos.getMinBlockX() - endPos.getMinBlockX();
        int maxY = own.minY() + own.height() - 1;
        Map<Long, EndBandSpill> spill = new HashMap<>();
        originals.forEach((n, g) -> {
            EndBandSpill s = EndBandSpill.diff(g, ring.get(n), shiftX, bedY, own.minY(), maxY, end.registryAccess(),
                    passIndex, pos.toLong());
            if (s != null) spill.put(EndBandSpill.displayChunkKey(n.x, n.z, chunkOffsetX), s);
        });
        return own.withSpill(Map.copyOf(spill));
    }

    /** The undecorated ground of End chunk {@code endPos} for look {@code style}, generated once and shared. */
    private static ProtoChunk cachedGround(ServerLevel end, NoiseBasedChunkGenerator noise, RandomState random,
                                           CycleLayout.Style style, ChunkPos endPos, GroundFill fill) {
        return GROUND.get(new EndBandGroundCache.Key(style, endPos.toLong()),
                () -> generateGround(end, noise, random, endPos, fill));
    }

    /** Noise, surface rules and carvers for one End chunk — everything but decoration. */
    private static ProtoChunk generateGround(ServerLevel end, NoiseBasedChunkGenerator noise, RandomState random,
                                             ChunkPos endPos, GroundFill fill) {
        ProtoChunk chunk = OfflineChunkSampler.blankSample(end, noise, random, endPos);
        OfflineChunkSampler.Workspace workspace = OfflineChunkSampler.workspaceFor(end, noise, random, chunk);
        ProtoChunk ground = fill.fill(end, noise, random, chunk, workspace);
        if (ground == null) return null;
        try {
            OfflineChunkSampler.carve(noise, random, ground, workspace, end.getSeed());
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] End-band carvers failed at {} — keeping uncarved terrain", endPos, t);
        }
        return ground;
    }

    /**
     * A deep copy of an undecorated ground chunk for one decoration pass to write into: block states copied
     * section by section, the biome containers shared (nothing writes biomes during decoration), at
     * {@code FEATURES} with its heightmaps primed like {@link OfflineChunkSampler#fillGround} so features
     * read and keep the right surface.
     */
    private static ProtoChunk copyGround(ServerLevel end, ProtoChunk source) {
        Registry<Biome> biomes = end.registryAccess().registryOrThrow(Registries.BIOME);
        LevelChunkSection[] from = source.getSections();
        LevelChunkSection[] to = new LevelChunkSection[from.length];
        for (int i = 0; i < from.length; i++) to[i] = copySection(from[i]);
        ProtoChunk copy = new ProtoChunk(source.getPos(), UpgradeData.EMPTY, to, new ProtoChunkTicks<>(),
                new ProtoChunkTicks<>(), end, biomes, null);
        Heightmap.primeHeightmaps(copy, EnumSet.of(
                Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG,
                Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));
        copy.setPersistedStatus(ChunkStatus.FEATURES);
        return copy;
    }

    /**
     * A fresh section holding {@code source}'s blocks, sharing its biomes. Written block by block rather than
     * through {@code PalettedContainer.copy()}: a copied single-value (all-air) container keeps its palette's
     * resize hook bound to the <i>original</i>, so the first block a feature writes into it fails
     * ("value 1 is not in the range 0 to 0") — and would resize the cached ground instead.
     */
    private static LevelChunkSection copySection(LevelChunkSection source) {
        PalettedContainer<BlockState> states = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, AIR, PalettedContainer.Strategy.SECTION_STATES);
        LevelChunkSection out = new LevelChunkSection(states, source.getBiomes());
        if (source.hasOnlyAir()) return out;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState s = source.getBlockState(x, y, z);
                    if (!s.isAir()) out.setBlockState(x, y, z, s, false);
                }
            }
        }
        return out;
    }

    /** Copy the End's island band out of the sample, shifted onto track level. */
    private static Result copyOut(ServerLevel end, ProtoChunk ground, ChunkPos pos, ChunkPos endPos, int bedY,
                                  int displayMinY, int displayMaxY) {
        int minY = Math.max(displayMinY, EndBandStyle.displayY(EndIslandGeometry.END_Y_SAMPLE_MIN, bedY));
        int maxY = Math.min(displayMaxY - 1, EndBandStyle.displayY(EndIslandGeometry.END_Y_SAMPLE_MAX, bedY));
        if (maxY < minY) return null;
        int height = maxY - minY + 1;
        int srcMin = end.getMinBuildHeight();
        int srcMax = end.getMaxBuildHeight();
        BlockState[] states = new BlockState[16 * 16 * height];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int endBaseX = endPos.getMinBlockX(), baseZ = endPos.getMinBlockZ();
        for (int ly = 0; ly < height; ly++) {
            int sy = EndBandStyle.endY(minY + ly, bedY);
            if (sy < srcMin || sy >= srcMax) continue;
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    states[Result.index(dx, ly, dz)] = ground.getBlockState(cursor.set(endBaseX + dx, sy, baseZ + dz));
                }
            }
        }
        int shiftX = pos.getMinBlockX() - endBaseX;
        Map<Long, CompoundTag> blockEntities = new HashMap<>();
        ground.getBlockEntityNbts().forEach((at, nbt) -> {
            // Vanilla's data-less placeholder: leave it out so the apply side creates the block entity fresh.
            if (!OfflineChunkSampler.isPlaceholderBlockEntity(nbt)) putBlockEntity(blockEntities, at, nbt.copy(), shiftX, bedY, minY, maxY);
        });
        ground.getBlockEntities().forEach((at, be) ->
                putBlockEntity(blockEntities, at, be.saveWithFullMetadata(end.registryAccess()), shiftX, bedY, minY, maxY));
        return new Result(pos, minY, height, states, Map.copyOf(blockEntities), Map.of());
    }

    private static void putBlockEntity(Map<Long, CompoundTag> out, BlockPos at, CompoundTag nbt,
                                       int shiftX, int bedY, int minY, int maxY) {
        int y = EndBandStyle.displayY(at.getY(), bedY);
        if (y < minY || y > maxY) return;
        out.put(BlockPos.asLong(at.getX() + shiftX, y, at.getZ()), nbt);
    }
}
