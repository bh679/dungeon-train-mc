package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.SpheresProgressionConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * <p>Same threading rules as {@link ForeignSphereSampler}: dedicated threads, never
 * {@code Util.backgroundExecutor()} ({@code fillFromNoise} schedules onto that pool and joins).</p>
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
                         Map<Long, CompoundTag> blockEntities) {

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
    private static volatile ExecutorService executor;

    private EndBandSampler() {}

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
        executor().execute(() -> {
            long t0 = System.nanoTime();
            try {
                Result result = sample(server, pos, passIndex, bedY, displayMinY, displayMaxY);
                if (result != null && epoch == EPOCH.get()) READY.add(result);
                else IN_FLIGHT.remove(pos.toLong());
            } catch (Throwable t) {
                IN_FLIGHT.remove(pos.toLong());
                LOGGER.warn("[DungeonTrain] End-band sample failed for chunk {} (pass {})", pos, passIndex, t);
            }
            GenProfiler.addNanos(GenProfiler.Bucket.END_BAND_SAMPLE, System.nanoTime() - t0);
        });
    }

    /** The next finished sample, or {@code null}. Server thread. */
    public static Result poll() {
        Result r = READY.poll();
        if (r != null) IN_FLIGHT.remove(r.pos().toLong());
        return r;
    }

    /** Drop every queued and finished job (server stopping / world change). */
    public static void clear() {
        EPOCH.incrementAndGet();
        READY.clear();
        IN_FLIGHT.clear();
    }

    private static ExecutorService executor() {
        ExecutorService e = executor;
        if (e != null) return e;
        synchronized (EndBandSampler.class) {
            if (executor == null) {
                AtomicInteger n = new AtomicInteger();
                executor = Executors.newFixedThreadPool(SpheresProgressionConfig.samplerThreads(), task -> {
                    Thread thread = new Thread(task, "DungeonTrain-endband-sampler-" + n.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                });
            }
            return executor;
        }
    }

    /** Generate the End chunk behind display chunk {@code pos} and copy it out. Sampler thread. */
    private static Result sample(MinecraftServer server, ChunkPos pos, long passIndex, int bedY,
                                 int displayMinY, int displayMaxY) {
        ServerLevel end = server.getLevel(Level.END);
        if (end == null) return null;
        ChunkGenerator generator = end.getChunkSource().getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator live)) return null;
        NoiseBasedChunkGenerator noise = live;
        if (WorldGenCycle.fromConfig().endStyleOfPass(passIndex) == CycleLayout.Style.BOP) {
            BopEnd.Built bop = BopEnd.get(server);
            if (bop == null) return null;
            noise = bop.generator();
        }
        // The live End's noise: the BoP generator's settings are a copy of the same End settings.
        RandomState random = end.getChunkSource().randomState();

        ChunkPos endPos = new ChunkPos(pos.x + EndBandStyle.endChunkOffsetX(passIndex), pos.z);
        ProtoChunk chunk = OfflineChunkSampler.blankSample(end, noise, random, endPos);
        OfflineChunkSampler.Workspace workspace = OfflineChunkSampler.workspaceFor(end, noise, random, chunk);
        ProtoChunk ground = OfflineChunkSampler.fillGround(end, noise, random, chunk, workspace);
        if (ground == null) return null;
        try {
            OfflineChunkSampler.carve(noise, random, ground, workspace, end.getSeed());
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] End-band carvers failed at {} — keeping uncarved terrain", endPos, t);
        }
        try {
            OfflineChunkSampler.decorate(noise, workspace, ground);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] End-band decoration failed at {} — keeping bare terrain", endPos, t);
        }
        return copyOut(end, ground, pos, endPos, bedY, displayMinY, displayMaxY);
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
        return new Result(pos, minY, height, states, Map.copyOf(blockEntities));
    }

    private static void putBlockEntity(Map<Long, CompoundTag> out, BlockPos at, CompoundTag nbt,
                                       int shiftX, int bedY, int minY, int maxY) {
        int y = EndBandStyle.displayY(at.getY(), bedY);
        if (y < minY || y > maxY) return;
        out.put(BlockPos.asLong(at.getX() + shiftX, y, at.getZ()), nbt);
    }
}
