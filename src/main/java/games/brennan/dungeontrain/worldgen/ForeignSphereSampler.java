package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.SpheresProgressionConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
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
 * Generates the terrain of the spheres band's <b>offline</b> spheres — those cut from the Nether or the
 * End, and any sphere built around a structure — one (sphere, chunk) at a time, off the server thread.
 *
 * <p>Each job runs the source dimension's real generator into a throwaway chunk at the display chunk's
 * own X/Z ({@link OfflineChunkSampler}): noise, surface rules, carvers, the sphere's structure if it
 * rolled one ({@link SphereStructures}), then the biome's full decoration — glowstone, fungi, chorus,
 * trees. The rows the sphere shows are then copied out, already shifted into display space by the
 * sphere's lift, with any block-entity NBT (structure chests keep their loot tables). A finished
 * {@link Result} waits in a queue for {@code WorldSpheresEvents} to write it into the live chunk on the
 * server thread.</p>
 *
 * <p>Runs on its own dedicated threads ({@code DungeonTrain-sphere-sampler-N}), never on
 * {@code Util.backgroundExecutor()}: {@code fillFromNoise} schedules onto that pool and joins, and a
 * job that joined from inside it would starve it (the chunk-dimension portal room's lesson).</p>
 */
public final class ForeignSphereSampler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * One sampled (sphere, chunk): {@code states} is {@code 16 × 16 × height} display-space blocks
     * starting at world Y {@code minY}, indexed {@link #index}; {@code blockEntities} maps packed display
     * {@link BlockPos} longs to their NBT.
     */
    public record Result(SphereField.Sphere sphere, ChunkPos pos, int minY, int height,
                         BlockState[] states, Map<Long, CompoundTag> blockEntities) {

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

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private static final Set<Long> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<Result> READY = new ConcurrentLinkedQueue<>();
    /** Bumped on server stop so a job still running for the old server drops its result. */
    private static final AtomicInteger EPOCH = new AtomicInteger();
    private static volatile ExecutorService executor;

    private ForeignSphereSampler() {}

    /**
     * Start sampling {@code sphere}'s terrain for the chunk at {@code pos}, unless that job is already
     * queued, running, or finished and waiting. Never blocks.
     */
    public static void request(ServerLevel overworld, SphereField.Sphere sphere, ChunkPos pos, long seed) {
        long key = jobKey(sphere, pos);
        if (!IN_FLIGHT.add(key)) return;
        MinecraftServer server = overworld.getServer();
        int displayMinY = overworld.getMinBuildHeight();
        int displayMaxY = overworld.getMaxBuildHeight();
        int epoch = EPOCH.get();
        executor().execute(() -> {
            long t0 = System.nanoTime();
            try {
                Result result = sample(server, sphere, pos, seed, displayMinY, displayMaxY);
                if (result != null && epoch == EPOCH.get()) READY.add(result);
                else IN_FLIGHT.remove(key);
            } catch (Throwable t) {
                IN_FLIGHT.remove(key);
                LOGGER.warn("[DungeonTrain] Sphere sample failed for {} sphere at ({}, {}, {}) chunk {}",
                        sphere.source(), sphere.cx(), sphere.cy(), sphere.cz(), pos, t);
            }
            GenProfiler.addNanos(GenProfiler.Bucket.SPHERES_FOREIGN_SAMPLE, System.nanoTime() - t0);
        });
    }

    /** The next finished sample, or {@code null}. Server thread. */
    public static Result poll() {
        Result r = READY.poll();
        if (r != null) IN_FLIGHT.remove(jobKey(r.sphere(), r.pos()));
        return r;
    }

    /** Drop every queued and finished job (server stopping / world change). */
    public static void clear() {
        EPOCH.incrementAndGet();
        READY.clear();
        IN_FLIGHT.clear();
        SphereStructures.clear();
    }

    private static long jobKey(SphereField.Sphere sphere, ChunkPos pos) {
        return sphere.id() * 0x9E3779B97F4A7C15L ^ pos.toLong();
    }

    private static ExecutorService executor() {
        ExecutorService e = executor;
        if (e != null) return e;
        synchronized (ForeignSphereSampler.class) {
            if (executor == null) {
                AtomicInteger n = new AtomicInteger();
                executor = Executors.newFixedThreadPool(SpheresProgressionConfig.samplerThreads(), task -> {
                    Thread thread = new Thread(task, "DungeonTrain-sphere-sampler-" + n.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                });
            }
            return executor;
        }
    }

    /** The level a sphere's terrain is generated in. */
    static ResourceKey<Level> levelKey(SphereSource source) {
        return switch (source) {
            case NETHER -> Level.NETHER;
            case END -> Level.END;
            case OVERWORLD -> Level.OVERWORLD;
        };
    }

    /** Generate and copy out one (sphere, chunk). Sampler thread. */
    private static Result sample(MinecraftServer server, SphereField.Sphere sphere, ChunkPos pos, long seed,
                                 int displayMinY, int displayMaxY) {
        ServerLevel level = server.getLevel(levelKey(sphere.source()));
        if (level == null) return null;
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) return null;
        RandomState random = level.getChunkSource().randomState();

        ProtoChunk chunk = OfflineChunkSampler.blankSample(level, noise, random, pos);
        OfflineChunkSampler.Workspace workspace = OfflineChunkSampler.workspaceFor(level, noise, random, chunk);
        ProtoChunk ground = OfflineChunkSampler.fillGround(level, noise, random, chunk, workspace);
        if (ground == null) return null;
        long worldSeed = level.getSeed();
        try {
            OfflineChunkSampler.carve(noise, random, ground, workspace, worldSeed);
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Sphere carvers failed at {} — keeping uncarved terrain", pos, t);
        }
        if (sphere.structure()) {
            SphereStructures.register(level, noise, random, ground, sphere, seed);
        }
        try {
            noise.applyBiomeDecoration(workspace.region(), ground, workspace.structures());
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Sphere decoration failed at {} — keeping bare terrain", pos, t);
        }
        return copyOut(level, ground, sphere, pos, displayMinY, displayMaxY);
    }

    /** Copy the sphere's rows out of the sample into display space (shifted up by the sphere's lift). */
    private static Result copyOut(ServerLevel level, ProtoChunk ground, SphereField.Sphere sphere, ChunkPos pos,
                                  int displayMinY, int displayMaxY) {
        int minY = Math.max(displayMinY, sphere.cy() - sphere.r());
        int maxY = Math.min(displayMaxY - 1, sphere.cy() + sphere.r());
        if (maxY < minY) return null;
        int height = maxY - minY + 1;
        int srcMin = level.getMinBuildHeight();
        int srcMax = level.getMaxBuildHeight();
        BlockState[] states = new BlockState[16 * 16 * height];
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int baseX = pos.getMinBlockX(), baseZ = pos.getMinBlockZ();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                if (!sphere.touchesColumn(baseX + dx, baseZ + dz)) continue;
                for (int ly = 0; ly < height; ly++) {
                    int sy = sphere.sourceY(minY + ly);
                    if (sy < srcMin || sy >= srcMax) continue;
                    states[Result.index(dx, ly, dz)] = ground.getBlockState(cursor.set(baseX + dx, sy, baseZ + dz));
                }
            }
        }
        Map<Long, CompoundTag> blockEntities = new HashMap<>();
        ground.getBlockEntityNbts().forEach((at, nbt) -> putBlockEntity(blockEntities, sphere, at, nbt.copy(), minY, maxY));
        ground.getBlockEntities().forEach((at, be) ->
                putBlockEntity(blockEntities, sphere, at, be.saveWithFullMetadata(level.registryAccess()), minY, maxY));
        return new Result(sphere, pos, minY, height, states, Map.copyOf(blockEntities));
    }

    private static void putBlockEntity(Map<Long, CompoundTag> out, SphereField.Sphere sphere, BlockPos at,
                                       CompoundTag nbt, int minY, int maxY) {
        int y = at.getY() + sphere.dy();
        if (y < minY || y > maxY || !sphere.contains(at.getX(), y, at.getZ())) return;
        out.put(BlockPos.asLong(at.getX(), y, at.getZ()), nbt);
    }
}
