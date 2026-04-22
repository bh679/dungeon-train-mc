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
 * The spawn-time bootstrap enqueues chunk positions instead of processing them
 * synchronously; the queue is drained by {@link #processPendingBootstrap} from
 * the level tick so login is not blocked. Ship-owned blocks are skipped via
 * {@link VSGameUtilsKt#getShipObjectManagingPos}.
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

    private static final int BOOTSTRAP_CHUNKS_PER_TICK = 2;

    private static final BlockState BED = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState PILLAR = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState RAIL_EAST_WEST = Blocks.RAIL.defaultBlockState()
            .setValue(RailBlock.SHAPE, RailShape.EAST_WEST);

    private static final Map<ResourceKey<Level>, Deque<Long>> PENDING_BOOTSTRAP = new HashMap<>();

    private TrackGenerator() {}

    /**
     * Place tracks within the intersection of {@code chunk} and {@code train}'s
     * Z strip. No-op if the chunk lies outside the strip.
     */
    public static void generateForChunk(ServerLevel level, ChunkAccess chunk, ServerShip train) {
        AABBdc aabb = train.getWorldAABB();
        int centerZ = (int) Math.floor((aabb.minZ() + aabb.maxZ()) * 0.5);
        int floorY = (int) Math.floor(aabb.minY());

        int bedY = floorY + BED_Y_OFFSET;
        int railY = floorY + RAIL_Y_OFFSET;
        int pillarTopY = floorY + PILLAR_Y_OFFSET;

        int minWorldY = level.getMinBuildHeight();
        if (bedY < minWorldY) return;

        int bedMinZ = centerZ - BED_HALF_WIDTH;
        int bedMaxZ = centerZ + BED_HALF_WIDTH;
        int railZA = centerZ - RAIL_Z_INNER_OFFSET;
        int railZB = centerZ + RAIL_Z_INNER_OFFSET;

        ChunkPos cp = chunk.getPos();
        int chunkMinZ = cp.getMinBlockZ();
        int chunkMaxZ = cp.getMaxBlockZ();
        if (bedMaxZ < chunkMinZ || bedMinZ > chunkMaxZ) return;

        int zStart = Math.max(bedMinZ, chunkMinZ);
        int zEnd = Math.min(bedMaxZ, chunkMaxZ);
        int xStart = cp.getMinBlockX();
        int xEnd = cp.getMaxBlockX();

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = xStart; x <= xEnd; x++) {
            for (int z = zStart; z <= zEnd; z++) {
                cursor.set(x, bedY, z);
                trySetBlock(level, cursor, BED);
            }
            if (railZA >= chunkMinZ && railZA <= chunkMaxZ) {
                cursor.set(x, railY, railZA);
                trySetBlock(level, cursor, RAIL_EAST_WEST);
            }
            if (railZB >= chunkMinZ && railZB <= chunkMaxZ) {
                cursor.set(x, railY, railZB);
                trySetBlock(level, cursor, RAIL_EAST_WEST);
            }
            if (centerZ >= chunkMinZ && centerZ <= chunkMaxZ && Math.floorMod(x, PILLAR_PERIOD) == 0) {
                placePillar(level, x, pillarTopY, centerZ, minWorldY);
            }
        }
    }

    /**
     * Enqueue every chunk position in {@code train}'s Z strip within server view
     * distance for deferred processing. Non-blocking — the actual block writes
     * happen across subsequent ticks via {@link #processPendingBootstrap}.
     *
     * Processing synchronously here would stall the server thread for seconds
     * at login and leave the client in perpetual movement-lock (VS physics
     * queue floods + login teleport never gets sent).
     */
    public static void bootstrapForTrain(ServerLevel level, ServerShip train) {
        AABBdc aabb = train.getWorldAABB();
        int centerZ = (int) Math.floor((aabb.minZ() + aabb.maxZ()) * 0.5);
        int shipChunkX = (int) Math.floor((aabb.minX() + aabb.maxX()) * 0.5) >> 4;

        int bedMinChunkZ = (centerZ - BED_HALF_WIDTH) >> 4;
        int bedMaxChunkZ = (centerZ + BED_HALF_WIDTH) >> 4;

        int radius = Math.max(8, level.getServer().getPlayerList().getViewDistance() + 2);

        Deque<Long> queue = PENDING_BOOTSTRAP.computeIfAbsent(level.dimension(), k -> new ArrayDeque<>());
        int enqueued = 0;
        for (int cx = shipChunkX - radius; cx <= shipChunkX + radius; cx++) {
            for (int cz = bedMinChunkZ; cz <= bedMaxChunkZ; cz++) {
                queue.offer(ChunkPos.asLong(cx, cz));
                enqueued++;
            }
        }
        LOGGER.info("[DungeonTrain] Track bootstrap enqueued {} chunk(s) around ship id={} (processing {}/tick)",
                enqueued, train.getId(), BOOTSTRAP_CHUNKS_PER_TICK);
    }

    /**
     * Drain up to {@link #BOOTSTRAP_CHUNKS_PER_TICK} queued chunk positions and
     * generate tracks in each. Cheap when the queue is empty.
     */
    public static void processPendingBootstrap(ServerLevel level, List<ServerShip> trains) {
        Deque<Long> queue = PENDING_BOOTSTRAP.get(level.dimension());
        if (queue == null || queue.isEmpty()) return;
        if (trains.isEmpty()) {
            queue.clear();
            return;
        }

        ServerChunkCache cache = level.getChunkSource();
        int processed = 0;
        while (processed < BOOTSTRAP_CHUNKS_PER_TICK && !queue.isEmpty()) {
            long packed = queue.poll();
            int cx = ChunkPos.getX(packed);
            int cz = ChunkPos.getZ(packed);
            LevelChunk chunk = cache.getChunkNow(cx, cz);
            if (chunk == null) continue;
            for (ServerShip train : trains) {
                generateForChunk(level, chunk, train);
            }
            processed++;
        }
    }

    private static void placePillar(ServerLevel level, int x, int topY, int z, int minWorldY) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = topY; y >= minWorldY; y--) {
            cursor.set(x, y, z);
            if (VSGameUtilsKt.getShipObjectManagingPos(level, cursor) != null) return;
            BlockState existing = level.getBlockState(cursor);
            if (existing.getBlock() == Blocks.STONE_BRICKS) return;
            if (existing.canOcclude()) return;
            level.setBlock(cursor, PILLAR, SET_BLOCK_FLAGS);
        }
    }

    private static void trySetBlock(ServerLevel level, BlockPos pos, BlockState state) {
        if (VSGameUtilsKt.getShipObjectManagingPos(level, pos) != null) return;
        level.setBlock(pos, state, SET_BLOCK_FLAGS);
    }
}
