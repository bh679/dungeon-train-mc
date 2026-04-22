package games.brennan.dungeontrain.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates a 5-wide stone-brick bridge deck with two parallel rails beneath
 * every Dungeon Train ship, dropping stone-brick pillars every {@link #PILLAR_PERIOD}
 * blocks wherever the deck hangs over empty space.
 *
 * Tracks live in world space (not ship space) so they stay behind as the train
 * rolls past. Placement is intentionally below the ship's world AABB so the
 * per-tick forward-clear pass in {@code TrainTickEvents} cannot eat them.
 *
 * Block placement uses flag {@link Block#UPDATE_CLIENTS} | {@link Block#UPDATE_KNOWN_SHAPE}
 * (18) — {@link Block#UPDATE_NEIGHBORS} is deliberately omitted. Rails otherwise
 * cascade through {@code RailBlock.neighborChanged} → {@code RailState.create},
 * rewriting every adjacent rail's shape and triggering its own neighbor pass,
 * which on a chunk-wide placement becomes O(N²) and stalls the server thread
 * for seconds (observed: 129-tick backlog on a 29-chunk bootstrap).
 *
 * The spawn-time bootstrap captures the train's target Y and Z centerline at
 * enqueue time into a {@link BootstrapState} and stores the chunk positions in
 * a queue drained one-per-tick by {@link #processPendingBootstrap}. The drain
 * does not require the live {@link ServerShip} — needed because VS has a
 * registration race where {@code getLoadedShips()} returns empty for several
 * ticks after {@code assembleToShip} returns, which previously blocked the
 * queue from draining at all.
 */
public final class TrackGenerator {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int BED_HALF_WIDTH = 2;
    private static final int BED_Y_OFFSET = -2;
    private static final int RAIL_Y_OFFSET = -1;
    private static final int RAIL_Z_INNER_OFFSET = 1;
    private static final int PILLAR_PERIOD = 8;
    private static final int PILLAR_Y_OFFSET = -3;

    private static final int SET_BLOCK_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** One chunk per tick keeps the server tick budget under ~200 setBlocks ≈ cheap. */
    private static final int BOOTSTRAP_CHUNKS_PER_TICK = 1;

    /** Wait this many ticks after enqueue before we start draining, so VS has time
     *  to register the ship and the client has time to sync its transform. Without
     *  this, block updates during the VS registration race flood the physics frame
     *  queue and the player snaps back on movement ("moved while colliding with
     *  unloaded ships"). */
    private static final int BOOTSTRAP_START_DELAY_TICKS = 40;

    /** Log drain progress at most this often per dimension (ticks ≈ 1s). */
    private static final int DRAIN_LOG_INTERVAL_TICKS = 20;

    private static final BlockState BED = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState PILLAR = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState RAIL_EAST_WEST = Blocks.RAIL.defaultBlockState()
            .setValue(RailBlock.SHAPE, RailShape.EAST_WEST);

    private static final Map<ResourceKey<Level>, BootstrapState> PENDING_BOOTSTRAP = new HashMap<>();

    /**
     * Per-dimension bootstrap queue + cached geometry snapshot. We cache
     * {@code centerZ} and {@code floorY} at enqueue time so the drain does not
     * need to consult the live ship — answering the user's question "does the
     * generation wait for the train? is that necessary if we already know the
     * position?" with "yes it used to, and no it wasn't necessary."
     */
    private static final class BootstrapState {
        final Deque<Long> queue = new ArrayDeque<>();
        int centerZ;
        int floorY;
        long shipId;
        int totalEnqueued;
        int drained;
        int ticksSinceEnqueue;
        int ticksSinceLogged;
    }

    private TrackGenerator() {}

    /**
     * Place tracks within the intersection of {@code chunk} and {@code train}'s
     * Z strip, using the train's live world AABB. Used by the
     * {@code ChunkEvent.Load} path where the train is already registered.
     */
    public static void generateForChunk(ServerLevel level, ChunkAccess chunk, ServerShip train) {
        AABBdc aabb = train.getWorldAABB();
        int centerZ = (int) Math.floor((aabb.minZ() + aabb.maxZ()) * 0.5);
        int floorY = (int) Math.floor(aabb.minY());
        generateForChunkAt(level, chunk, centerZ, floorY);
    }

    /**
     * Place tracks using explicit {@code centerZ} / {@code floorY} — no ship
     * lookup. Used by the bootstrap drain with cached geometry.
     */
    public static void generateForChunkAt(ServerLevel level, ChunkAccess chunk, int centerZ, int floorY) {
        int bedY = floorY + BED_Y_OFFSET;
        int railY = floorY + RAIL_Y_OFFSET;
        int pillarTopY = floorY + PILLAR_Y_OFFSET;

        int minWorldY = level.getMinBuildHeight();
        if (bedY < minWorldY) {
            LOGGER.debug("[DungeonTrain] Skip chunk {}: bedY={} below minBuildHeight={}", chunk.getPos(), bedY, minWorldY);
            return;
        }

        int bedMinZ = centerZ - BED_HALF_WIDTH;
        int bedMaxZ = centerZ + BED_HALF_WIDTH;
        int railZA = centerZ - RAIL_Z_INNER_OFFSET;
        int railZB = centerZ + RAIL_Z_INNER_OFFSET;

        ChunkPos cp = chunk.getPos();
        int chunkMinZ = cp.getMinBlockZ();
        int chunkMaxZ = cp.getMaxBlockZ();
        if (bedMaxZ < chunkMinZ || bedMinZ > chunkMaxZ) {
            LOGGER.debug("[DungeonTrain] Skip chunk {}: Z strip [{}..{}] outside chunk Z [{}..{}]",
                    cp, bedMinZ, bedMaxZ, chunkMinZ, chunkMaxZ);
            return;
        }

        int zStart = Math.max(bedMinZ, chunkMinZ);
        int zEnd = Math.min(bedMaxZ, chunkMaxZ);
        int xStart = cp.getMinBlockX();
        int xEnd = cp.getMaxBlockX();

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int bedCount = 0;
        int railCount = 0;
        int pillarBlockCount = 0;
        for (int x = xStart; x <= xEnd; x++) {
            for (int z = zStart; z <= zEnd; z++) {
                cursor.set(x, bedY, z);
                if (trySetBlock(level, cursor, BED)) bedCount++;
            }
            if (railZA >= chunkMinZ && railZA <= chunkMaxZ) {
                cursor.set(x, railY, railZA);
                if (trySetBlock(level, cursor, RAIL_EAST_WEST)) railCount++;
            }
            if (railZB >= chunkMinZ && railZB <= chunkMaxZ) {
                cursor.set(x, railY, railZB);
                if (trySetBlock(level, cursor, RAIL_EAST_WEST)) railCount++;
            }
            if (centerZ >= chunkMinZ && centerZ <= chunkMaxZ && Math.floorMod(x, PILLAR_PERIOD) == 0) {
                pillarBlockCount += placePillar(level, x, pillarTopY, centerZ, minWorldY);
            }
        }
        LOGGER.debug("[DungeonTrain] Painted chunk {}: bed={} rails={} pillarBlocks={} (bedY={}, railY={})",
                cp, bedCount, railCount, pillarBlockCount, bedY, railY);
    }

    /**
     * Enqueue chunk positions around {@code train} for deferred generation,
     * snapshotting the train's current Z centerline and floor Y into the state.
     * Non-blocking — the actual block writes happen across subsequent ticks via
     * {@link #processPendingBootstrap}.
     */
    public static void bootstrapForTrain(ServerLevel level, ServerShip train) {
        AABBdc aabb = train.getWorldAABB();
        int centerZ = (int) Math.floor((aabb.minZ() + aabb.maxZ()) * 0.5);
        int floorY = (int) Math.floor(aabb.minY());
        int shipChunkX = (int) Math.floor((aabb.minX() + aabb.maxX()) * 0.5) >> 4;

        int bedMinChunkZ = (centerZ - BED_HALF_WIDTH) >> 4;
        int bedMaxChunkZ = (centerZ + BED_HALF_WIDTH) >> 4;

        int radius = Math.max(8, level.getServer().getPlayerList().getViewDistance() + 2);

        BootstrapState state = PENDING_BOOTSTRAP.computeIfAbsent(level.dimension(), k -> new BootstrapState());
        // Refresh cached geometry on every bootstrap call — if the train moved
        // before the previous queue finished, new chunks use the new Y/Z and the
        // old queued ones finish with the old values (idempotent either way).
        state.centerZ = centerZ;
        state.floorY = floorY;
        state.shipId = train.getId();
        state.ticksSinceEnqueue = 0;
        state.ticksSinceLogged = 0;

        int enqueued = 0;
        for (int cx = shipChunkX - radius; cx <= shipChunkX + radius; cx++) {
            for (int cz = bedMinChunkZ; cz <= bedMaxChunkZ; cz++) {
                state.queue.offer(ChunkPos.asLong(cx, cz));
                enqueued++;
            }
        }
        state.totalEnqueued += enqueued;
        LOGGER.info("[DungeonTrain] Track bootstrap enqueued {} chunk(s) around ship id={} "
                        + "(centerZ={}, floorY={}, bedY={}, railY={}, chunksPerTick={}, startDelayTicks={}, queueSize={})",
                enqueued, train.getId(), centerZ, floorY,
                floorY + BED_Y_OFFSET, floorY + RAIL_Y_OFFSET,
                BOOTSTRAP_CHUNKS_PER_TICK, BOOTSTRAP_START_DELAY_TICKS, state.queue.size());
    }

    /**
     * Drain up to {@link #BOOTSTRAP_CHUNKS_PER_TICK} queued chunk positions.
     * Uses cached geometry from {@link BootstrapState} — does NOT consult the
     * live ship list, so VS registration races do not stall the queue.
     *
     * @param trains unused (kept for call-site compatibility); drain is
     *               geometry-snapshot driven, not ship-driven.
     */
    public static void processPendingBootstrap(ServerLevel level, List<ServerShip> trains) {
        BootstrapState state = PENDING_BOOTSTRAP.get(level.dimension());
        if (state == null) return;
        if (state.queue.isEmpty()) return;

        state.ticksSinceEnqueue++;
        state.ticksSinceLogged++;

        if (state.ticksSinceEnqueue < BOOTSTRAP_START_DELAY_TICKS) {
            if (state.ticksSinceEnqueue == 1) {
                LOGGER.info("[DungeonTrain] Track bootstrap queued for ship id={} — waiting {} ticks before draining "
                                + "(lets VS register the ship and client sync its transform)",
                        state.shipId, BOOTSTRAP_START_DELAY_TICKS);
            }
            return;
        }

        ServerChunkCache cache = level.getChunkSource();
        int processed = 0;
        int skippedUnloaded = 0;
        while (processed < BOOTSTRAP_CHUNKS_PER_TICK && !state.queue.isEmpty()) {
            long packed = state.queue.poll();
            int cx = ChunkPos.getX(packed);
            int cz = ChunkPos.getZ(packed);
            LevelChunk chunk = cache.getChunkNow(cx, cz);
            if (chunk == null) {
                skippedUnloaded++;
                continue;
            }
            generateForChunkAt(level, chunk, state.centerZ, state.floorY);
            processed++;
            state.drained++;
        }

        if (state.ticksSinceLogged >= DRAIN_LOG_INTERVAL_TICKS || state.queue.isEmpty()) {
            LOGGER.info("[DungeonTrain] Track drain tick: processed={} skippedUnloaded={} drainedTotal={}/{} remaining={} shipId={}",
                    processed, skippedUnloaded, state.drained, state.totalEnqueued, state.queue.size(), state.shipId);
            state.ticksSinceLogged = 0;
        }

        if (state.queue.isEmpty()) {
            LOGGER.info("[DungeonTrain] Track bootstrap COMPLETE for ship id={} — painted {} chunk(s) total",
                    state.shipId, state.drained);
            PENDING_BOOTSTRAP.remove(level.dimension());
        }
    }

    /**
     * @return number of pillar blocks actually placed (for logging).
     */
    private static int placePillar(ServerLevel level, int x, int topY, int z, int minWorldY) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int placed = 0;
        for (int y = topY; y >= minWorldY; y--) {
            cursor.set(x, y, z);
            if (VSGameUtilsKt.getShipObjectManagingPos(level, cursor) != null) return placed;
            BlockState existing = level.getBlockState(cursor);
            // Existing stone brick = previously placed pillar, we're done (idempotent).
            // Any other occluding block = natural ground giving the pillar support.
            if (existing.getBlock() == Blocks.STONE_BRICKS) return placed;
            if (existing.canOcclude()) return placed;
            level.setBlock(cursor, PILLAR, SET_BLOCK_FLAGS);
            placed++;
        }
        return placed;
    }

    /**
     * @return true if a block was actually written (false if skipped due to ship
     *         ownership), for paint-statistic accounting.
     */
    private static boolean trySetBlock(ServerLevel level, BlockPos pos, BlockState state) {
        if (VSGameUtilsKt.getShipObjectManagingPos(level, pos) != null) return false;
        level.setBlock(pos, state, SET_BLOCK_FLAGS);
        return true;
    }
}
