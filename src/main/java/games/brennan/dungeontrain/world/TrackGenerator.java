package games.brennan.dungeontrain.world;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
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

/**
 * Generates a 5-wide stone-brick bridge deck with two parallel rails beneath
 * every Dungeon Train ship, dropping stone-brick pillars every {@link #PILLAR_PERIOD}
 * blocks wherever the deck hangs over empty space.
 *
 * Tracks live in world space (not ship space) so they stay behind as the train
 * rolls past. Placement is intentionally below the ship's world AABB so the
 * per-tick forward-clear pass in {@code TrainTickEvents} cannot eat them.
 *
 * This class is pure: all geometry is derived from the train's live world AABB,
 * so it works unchanged if the spawn origin or Y changes. Ship-owned blocks are
 * never overwritten (every {@code setBlock} is gated on
 * {@link VSGameUtilsKt#getShipObjectManagingPos}).
 */
public final class TrackGenerator {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int BED_HALF_WIDTH = 2;
    private static final int BED_Y_OFFSET = -2;
    private static final int RAIL_Y_OFFSET = -1;
    private static final int RAIL_Z_INNER_OFFSET = 1;
    private static final int PILLAR_PERIOD = 8;
    private static final int PILLAR_Y_OFFSET = -3;

    private static final int SET_BLOCK_FLAGS = 3;

    private static final BlockState BED = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState PILLAR = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState RAIL_EAST_WEST = Blocks.RAIL.defaultBlockState()
            .setValue(RailBlock.SHAPE, RailShape.EAST_WEST);

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
     * Fill currently-loaded chunks in {@code train}'s Z strip. Needed on spawn
     * because ChunkEvent.Load has already fired for these chunks before the
     * train existed. We walk a generous X radius around the ship; {@code
     * getChunkNow} returns null for positions that aren't loaded, so we skip
     * them without forcing generation.
     */
    public static void bootstrapForTrain(ServerLevel level, ServerShip train) {
        AABBdc aabb = train.getWorldAABB();
        int centerZ = (int) Math.floor((aabb.minZ() + aabb.maxZ()) * 0.5);
        int shipChunkX = (int) Math.floor(((aabb.minX() + aabb.maxX()) * 0.5)) >> 4;

        int bedMinChunkZ = (centerZ - BED_HALF_WIDTH) >> 4;
        int bedMaxChunkZ = (centerZ + BED_HALF_WIDTH) >> 4;

        int radius = Math.max(8, level.getServer().getPlayerList().getViewDistance() + 2);

        ServerChunkCache cache = level.getChunkSource();
        int filled = 0;
        for (int cx = shipChunkX - radius; cx <= shipChunkX + radius; cx++) {
            for (int cz = bedMinChunkZ; cz <= bedMaxChunkZ; cz++) {
                LevelChunk chunk = cache.getChunkNow(cx, cz);
                if (chunk == null) continue;
                generateForChunk(level, chunk, train);
                filled++;
            }
        }
        LOGGER.info("[DungeonTrain] Track bootstrap placed in {} loaded chunk(s) around ship id={}", filled, train.getId());
    }

    private static void placePillar(ServerLevel level, int x, int topY, int z, int minWorldY) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = topY; y >= minWorldY; y--) {
            cursor.set(x, y, z);
            if (VSGameUtilsKt.getShipObjectManagingPos(level, cursor) != null) return;
            BlockState existing = level.getBlockState(cursor);
            // Existing stone brick = previously placed pillar, we're done (idempotent).
            // Any other occluding block = natural ground giving the pillar support.
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
