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

import java.util.Deque;
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
     * per-tick server-thread cost. Now that ChunkEvent.Load only enqueues
     * (no synchronous setBlocks on the chunk-load tick) the original 17-sec
     * server-thread wedge is gone and we can safely push this up.
     *
     * <p>One chunk is ~80 columns × ~2 setBlocks + pillar descent = up to
     * ~250 block changes per chunk. Budget 4 → ≤1000 setBlocks per call,
     * fires every {@code TRACK_FILL_PERIOD_TICKS=10} ticks, averages
     * ~100/tick server-thread cost — still well under the 50 ms budget.
     * 4/period × 2 periods/sec = 8 chunks/sec fill rate, enough to keep
     * up with a walking player's loaded-chunk stream.</p>
     */
    private static final int CHUNKS_PER_SCAN_BUDGET = 4;

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

        // 3. Pillar — only at every Nth X. placePillar scans down from the
        // bed itself, so the "bed is sitting on solid ground" case is handled
        // by the first iteration finding non-passable terrain and returning.
        if (Math.floorMod(worldX, PILLAR_SPACING) == 0) {
            placePillar(level, worldX, worldZ, g.bedY() - 1);
        }
    }

    /**
     * Drop a pillar column from {@code pillarTopInclusive} downward until it
     * hits real terrain (motion-blocking, not water, not leaves, not vines).
     * Passes through air, any fluid (so pillars reach the seafloor through
     * ocean water), leaves, and vines — heightmaps like
     * {@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES} can't be used here
     * because they count water surfaces as "ground" and would leave pillars
     * floating above the ocean; {@link Heightmap.Types#OCEAN_FLOOR} counts
     * leaves as ground instead. A manual descent handles both biomes.
     *
     * <p>If the first block scanned is already solid (mountain, existing
     * stone brick), returns immediately without placing — that's the "bed
     * has natural support" case, no pillar needed.</p>
     */
    private static void placePillar(ServerLevel level, int worldX, int worldZ, int pillarTopInclusive) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight() + 1;
        int placed = 0;
        for (int py = pillarTopInclusive; py >= minY; py--) {
            pos.set(worldX, py, worldZ);
            if (VSGameUtilsKt.getShipObjectManagingPos(level, pos) != null) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("[DungeonTrain] Pillar at ({},{}) STOP at y={} (ship-managed) placed={}",
                        worldX, worldZ, py, placed);
                }
                return;
            }
            BlockState existing = level.getBlockState(pos);
            boolean passable = existing.isAir()
                || !existing.getFluidState().isEmpty()
                || existing.is(BlockTags.LEAVES)
                || existing.is(Blocks.VINE);
            if (!passable) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("[DungeonTrain] Pillar at ({},{}) STOP at y={} hit={} placed={}",
                        worldX, worldZ, py, existing.getBlock().builtInRegistryHolder().key().location(), placed);
                }
                return;
            }
            level.setBlock(pos, TrackPalette.PILLAR, Block.UPDATE_CLIENTS);
            placed++;
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[DungeonTrain] Pillar at ({},{}) reached minY={} without support, placed={}",
                worldX, worldZ, minY, placed);
        }
    }

    /**
     * Drain up to {@link #CHUNKS_PER_SCAN_BUDGET} chunks of work per call:
     * first from the pending-chunk queue populated by {@code TrackChunkEvents}
     * (covers chunks loaded after spawn, anywhere in the world the train
     * corridor reaches), then by walking the view-distance box around the
     * train on X × ±{@link #Z_CHUNK_MARGIN} on Z (covers chunks already loaded
     * at spawn time that never re-fire Load).
     *
     * <p>The budget is shared across both sources so a single call does at
     * most {@code CHUNKS_PER_SCAN_BUDGET} block-writing passes — keeps the
     * tick cost flat regardless of how many chunks loaded recently.</p>
     */
    public static void fillRenderDistance(ServerLevel level, ServerShip ship, TrainTransformProvider provider) {
        TrackGeometry g = provider.getTrackGeometry();
        if (g == null) return;

        Set<Long> filled = provider.getFilledChunks();
        Deque<Long> pending = provider.getPendingChunks();
        int budget = CHUNKS_PER_SCAN_BUDGET;
        int drainedFromPending = 0;

        // 1. Drain the pending queue first — FIFO so nearby chunks (loaded
        //    first around the player) paint before far-away chunks. poll()
        //    is O(1) and removes the head atomically.
        while (budget > 0) {
            Long key = pending.poll();
            if (key == null) break;
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            if (filled.contains(key)) continue;
            if (isShipyardChunk(cx, cz)) continue;
            if (!level.getChunkSource().hasChunk(cx, cz)) continue;
            ensureTracksForChunk(level, cx, cz, g, filled);
            budget--;
            drainedFromPending++;
        }

        // 2. Then sweep the view-distance corridor for anything still missing
        //    (chunks loaded at spawn before TrackChunkEvents registered, or
        //    anything dropped from pending because filled/shipyard/unloaded).
        int scanned = 0;
        if (budget > 0) {
            int viewDistance = level.getServer().getPlayerList().getViewDistance();
            if (viewDistance <= 0) viewDistance = 10; // dedicated-server fallback

            Vector3dc shipWorldPos = ship.getTransform().getPosition();
            int centerCx = (int) Math.floor(shipWorldPos.x()) >> 4;
            int centerCz = g.trackCenterZ() >> 4;

            for (int cz = centerCz - Z_CHUNK_MARGIN; cz <= centerCz + Z_CHUNK_MARGIN && budget > 0; cz++) {
                for (int cx = centerCx - viewDistance; cx <= centerCx + viewDistance && budget > 0; cx++) {
                    if (isShipyardChunk(cx, cz)) continue;
                    long key = ChunkPos.asLong(cx, cz);
                    if (filled.contains(key)) continue;
                    if (!level.getChunkSource().hasChunk(cx, cz)) continue;

                    ensureTracksForChunk(level, cx, cz, g, filled);
                    budget--;
                    scanned++;
                }
            }
        }

        if (budget < CHUNKS_PER_SCAN_BUDGET && LOGGER.isDebugEnabled()) {
            LOGGER.debug("[DungeonTrain] fillRenderDistance ship={} drained={} (pending={} scan={}) filled.size={} pending.size={}",
                ship.getId(), CHUNKS_PER_SCAN_BUDGET - budget, drainedFromPending, scanned,
                filled.size(), pending.size());
        }
    }
}
