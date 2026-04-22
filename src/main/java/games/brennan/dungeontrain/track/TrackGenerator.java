package games.brennan.dungeontrain.track;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.Set;

/**
 * Stateless helpers that fill a chunk — or a batch of chunks in the render
 * corridor — with a 5-wide stone-brick track bed, rails, and pillars-where-
 * needed under a running Dungeon Train.
 *
 * <p>All methods run on the server thread. A per-train
 * {@code Set<Long>} (chunk-pos longs) cache lives on
 * {@link TrainTransformProvider#getFilledChunks()} — once a chunk has been
 * processed, it's never iterated again, so periodic scans cost only the set
 * lookups.</p>
 *
 * <p>To keep any single tick from blowing the server tick budget on a
 * large spawn (e.g. 20 carriages × 21 chunks of render distance × ~80
 * columns = 30k+ {@code setBlock} calls), fills are batched — at most
 * {@link #CHUNKS_PER_SCAN_BUDGET} previously-unprocessed chunks per
 * periodic call.</p>
 */
public final class TrackGenerator {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Pillars drop every this-many blocks along X. */
    private static final int PILLAR_SPACING = 8;

    /**
     * Normal-world gameplay is bounded far below this. Anything beyond is the
     * VS2 shipyard — skip to avoid polluting ship voxel storage.
     */
    private static final long SHIPYARD_COORDINATE_CUTOFF = 10_000_000L;

    /** Fixed-size Z margin when computing chunk corridor — 5-wide track always fits in ±1 chunk. */
    private static final int Z_CHUNK_MARGIN = 1;

    /**
     * Max new (unprocessed) chunks to fill per periodic scan. Caps the
     * per-tick server-thread cost — a freshly-spawned 20-carriage train
     * would otherwise try to fill 60+ chunks in one tick, blocking the
     * server thread long enough to stall VS's physics queue.
     *
     * <p>One chunk's worth of placement is ~80 columns × ~2 setBlock calls =
     * ~160 block changes per scan, ~16ms on server thread. A full render-
     * distance corridor (~60 chunks) completes in ~3 seconds of in-game
     * time — subsequent scans hit the cache and exit instantly.</p>
     */
    private static final int CHUNKS_PER_SCAN_BUDGET = 1;

    private TrackGenerator() {}

    /**
     * Fast coordinate-range filter for VS2 shipyard chunks. VS stores ship
     * voxels in the same dimension but at coordinates |x| ≤ ~28M, z in
     * ~12M..28M. Normal gameplay never reaches ±10M, so anything past that
     * cutoff is certainly shipyard.
     */
    public static boolean isShipyardChunk(int cx, int cz) {
        long worldX = ((long) cx) << 4;
        long worldZ = ((long) cz) << 4;
        return Math.abs(worldX) > SHIPYARD_COORDINATE_CUTOFF
            || Math.abs(worldZ) > SHIPYARD_COORDINATE_CUTOFF;
    }

    /**
     * Ensure tracks exist in the given chunk for {@code g}. Hit the provider's
     * cache first — chunks already processed exit in O(1). On miss, iterate
     * the chunk's columns and add to the cache on completion.
     */
    public static void ensureTracksForChunk(
        ServerLevel level,
        int chunkX,
        int chunkZ,
        TrackGeometry g,
        Set<Long> filledChunks
    ) {
        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        if (filledChunks.contains(chunkKey)) return;

        int chunkMinX = chunkX << 4;
        int chunkMinZ = chunkZ << 4;
        int chunkMaxZ = chunkMinZ + 15;

        // Z-corridor intersection test — mark out-of-corridor chunks as
        // processed too so we never look at them again.
        if (chunkMaxZ < g.trackZMin() || chunkMinZ > g.trackZMax()) {
            filledChunks.add(chunkKey);
            return;
        }

        int zLo = Math.max(g.trackZMin(), chunkMinZ);
        int zHi = Math.min(g.trackZMax(), chunkMaxZ);

        for (int localX = 0; localX < 16; localX++) {
            int worldX = chunkMinX + localX;
            for (int worldZ = zLo; worldZ <= zHi; worldZ++) {
                placeTrackColumn(level, worldX, worldZ, g);
            }
        }

        filledChunks.add(chunkKey);
    }

    /**
     * Place bed + (optional) rail + (optional) pillar at one (x, z) column.
     * Skips if the bed position is inside a VS ship or is already laid.
     */
    private static void placeTrackColumn(ServerLevel level, int worldX, int worldZ, TrackGeometry g) {
        BlockPos bedPos = new BlockPos(worldX, g.bedY(), worldZ);

        // Skip ship-owned positions — never mutate voxels that belong to our
        // train or any other VS ship sharing this dimension.
        if (VSGameUtilsKt.getShipObjectManagingPos(level, bedPos) != null) return;

        // Second-line idempotence — if the bed is already stone brick,
        // something already placed it (a neighbouring train, a previous
        // cache-cleared scan, etc). Don't overwrite.
        BlockState existingBed = level.getBlockState(bedPos);
        if (existingBed.is(TrackPalette.BED.getBlock())) return;

        // 1. Bed.
        level.setBlock(bedPos, TrackPalette.BED, Block.UPDATE_CLIENTS);

        // 2. Rails on top, at the two inner-edge Z rows (Z+1 and Z-1 relative
        // to the 5-wide corridor). For a 5-wide corridor these are trackZMin+1
        // and trackZMax-1 — a 2-block gauge between rails.
        if (worldZ == g.trackZMin() + 1 || worldZ == g.trackZMax() - 1) {
            BlockPos railPos = new BlockPos(worldX, g.railY(), worldZ);
            if (VSGameUtilsKt.getShipObjectManagingPos(level, railPos) == null) {
                level.setBlock(railPos, TrackPalette.RAIL, Block.UPDATE_CLIENTS);
            }
        }

        // 3. Pillar — only at every Nth X, and only when ground is below the
        // bed (bridge case: over air/water/ravine).
        if (Math.floorMod(worldX, PILLAR_SPACING) == 0) {
            int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
            if (groundY < g.bedY() - 1) {
                placePillar(level, worldX, worldZ, groundY, g.bedY() - 1);
            }
        }
    }

    /**
     * Fill a pillar column from max(groundY, minBuildHeight+1) up to
     * pillarTopInclusive. Only replaces air, water, and leaves — preserves any
     * existing terrain that happens to be in the pillar's path.
     */
    private static void placePillar(ServerLevel level, int worldX, int worldZ, int groundY, int pillarTopInclusive) {
        int pillarBottom = Math.max(groundY, level.getMinBuildHeight() + 1);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int py = pillarBottom; py <= pillarTopInclusive; py++) {
            pos.set(worldX, py, worldZ);
            if (VSGameUtilsKt.getShipObjectManagingPos(level, pos) != null) continue;
            BlockState existing = level.getBlockState(pos);
            if (existing.isAir()
                || !existing.getFluidState().isEmpty()
                || existing.is(BlockTags.LEAVES)
                || existing.is(Blocks.VINE)) {
                level.setBlock(pos, TrackPalette.PILLAR, Block.UPDATE_CLIENTS);
            }
        }
    }

    /**
     * Walk every loaded chunk within server view distance of the train (on X)
     * times ±1 chunk (on Z) and fill any that haven't been processed yet.
     * Bounded by {@link #CHUNKS_PER_SCAN_BUDGET} to spread large initial
     * fills across multiple ticks.
     */
    public static void fillRenderDistance(ServerLevel level, ServerShip ship, TrainTransformProvider provider) {
        TrackGeometry g = provider.getTrackGeometry();
        if (g == null) return;

        Set<Long> filled = provider.getFilledChunks();
        int viewDistance = level.getServer().getPlayerList().getViewDistance();
        if (viewDistance <= 0) viewDistance = 10; // dedicated-server fallback

        Vector3dc shipWorldPos = ship.getTransform().getPosition();
        int centerCx = (int) Math.floor(shipWorldPos.x()) >> 4;
        int centerCz = g.trackCenterZ() >> 4;

        int budget = CHUNKS_PER_SCAN_BUDGET;
        for (int cz = centerCz - Z_CHUNK_MARGIN; cz <= centerCz + Z_CHUNK_MARGIN && budget > 0; cz++) {
            for (int cx = centerCx - viewDistance; cx <= centerCx + viewDistance && budget > 0; cx++) {
                if (isShipyardChunk(cx, cz)) continue;
                long key = ChunkPos.asLong(cx, cz);
                if (filled.contains(key)) continue;
                if (!level.getChunkSource().hasChunk(cx, cz)) continue;

                ensureTracksForChunk(level, cx, cz, g, filled);
                budget--;
            }
        }

        if (budget < CHUNKS_PER_SCAN_BUDGET && LOGGER.isDebugEnabled()) {
            LOGGER.debug("[DungeonTrain] fillRenderDistance: filled {} new chunks for ship {} (cache size {})",
                CHUNKS_PER_SCAN_BUDGET - budget, ship.getId(), filled.size());
        }
    }
}
