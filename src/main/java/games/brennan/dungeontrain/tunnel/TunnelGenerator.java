package games.brennan.dungeontrain.tunnel;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.Iterator;
import java.util.Set;

/**
 * Stateless helpers that paint a stone-brick tunnel around the train's path
 * wherever the rock overhead is thick enough. Mirrors {@link TrackGenerator}:
 * chunk-driven deferred fill with a per-train cache, one chunk per scan.
 *
 * <p>Per qualified X column the tunnel adds: a floor extension outward from
 * the existing track bed to 11 blocks wide, an 11-wide × 8-tall airspace
 * (rails preserved), two full-height stone-brick wall columns at z = wallMinZ
 * and z = wallMaxZ, and a 13-wide stone-brick ceiling row.</p>
 *
 * <p>At entrance / exit transitions (first and last qualified X along the
 * train corridor), a 4-tier stepped stone-brick pyramid with stair-smoothed
 * edges is placed directly above the tunnel ceiling.</p>
 */
public final class TunnelGenerator {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 13-wide tunnel needs ±2 chunks on Z to be covered by the view-distance sweep. */
    private static final int Z_CHUNK_MARGIN = 2;

    /** Max previously-unprocessed chunks to fill per periodic scan — same budget as track fill. */
    private static final int CHUNKS_PER_SCAN_BUDGET = 1;

    /** Half-widths of each pyramid tier. Widths are {11, 9, 7, 5}. */
    private static final int[] PYRAMID_HALF_WIDTHS = { 5, 4, 3, 2 };

    private TunnelGenerator() {}

    /**
     * Ensure tunnel blocks exist in the given chunk for the given track geometry.
     * Caches the chunk key on completion so subsequent calls exit in O(1).
     */
    public static void ensureTunnelForChunk(
        ServerLevel level, int chunkX, int chunkZ,
        TrackGeometry g, Set<Long> tunnelFilledChunks
    ) {
        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        if (tunnelFilledChunks.contains(chunkKey)) return;

        TunnelGeometry tg = TunnelGeometry.from(g);

        int chunkMinX = chunkX << 4;
        int chunkMaxX = chunkMinX + 15;
        int chunkMinZ = chunkZ << 4;
        int chunkMaxZ = chunkMinZ + 15;

        // Fast Z-corridor prefilter — the tunnel spans z ∈ [wallMinZ, wallMaxZ]
        // (13 wide). Chunks fully outside that range can never contain tunnel
        // blocks; mark processed so we never look at them again.
        if (chunkMaxZ < tg.wallMinZ() || chunkMinZ > tg.wallMaxZ()) {
            tunnelFilledChunks.add(chunkKey);
            return;
        }

        // Walk X columns left-to-right carrying the previous qualification
        // flag — each column runs one underground check (6 block reads).
        // Seeding prev with the block at chunkMinX - 1 catches a transition
        // that straddles the chunk's left boundary.
        boolean prev = isColumnUnderground(level, chunkMinX - 1, tg);
        for (int worldX = chunkMinX; worldX <= chunkMaxX; worldX++) {
            boolean curr = isColumnUnderground(level, worldX, tg);
            if (curr && !prev) {
                placePortalPyramid(level, worldX, tg);
            } else if (!curr && prev) {
                placePortalPyramid(level, worldX - 1, tg);
            }
            if (curr) {
                paintTunnelColumn(level, worldX, tg);
            }
            prev = curr;
        }

        tunnelFilledChunks.add(chunkKey);
    }

    /**
     * Six-sample underground check: at y = ceilingY+1 and ceilingY+2, for
     * three Z positions spanning the track corridor (min, center, max), every
     * sampled block must be a natural underground material. Any air, water,
     * plant, or wood disqualifies the column.
     */
    static boolean isColumnUnderground(ServerLevel level, int worldX, TunnelGeometry tg) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int[] ys = { tg.ceilingY() + 1, tg.ceilingY() + 2 };
        int[] zs = { tg.trackZMin(), tg.centerZ(), tg.trackZMax() };
        for (int y : ys) {
            for (int z : zs) {
                pos.set(worldX, y, z);
                BlockState state = level.getBlockState(pos);
                if (!TunnelPalette.isUndergroundMaterial(state)) return false;
            }
        }
        return true;
    }

    private static void paintTunnelColumn(ServerLevel level, int worldX, TunnelGeometry tg) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // 1. Extend floor outward. [trackZMin..trackZMax] is the existing stone-brick
        //    bed (from TrackGenerator); tunnel floor needs 11-wide coverage.
        for (int z = tg.airMinZ(); z <= tg.airMaxZ(); z++) {
            if (z >= tg.trackZMin() && z <= tg.trackZMax()) continue;
            setIfNeeded(level, pos, worldX, tg.floorY(), z, TunnelPalette.FLOOR);
        }

        // 2. Airspace: 11 wide × 8 tall between floor and ceiling, cleared to air.
        //    Preserve rails at y=railY, z=railZMin|railZMax (placed by TrackGenerator).
        int airYMin = tg.railY();
        int airYMax = tg.ceilingY() - 1;
        for (int y = airYMin; y <= airYMax; y++) {
            for (int z = tg.airMinZ(); z <= tg.airMaxZ(); z++) {
                if (y == tg.railY() && (z == tg.railZMin() || z == tg.railZMax())) continue;
                pos.set(worldX, y, z);
                if (VSGameUtilsKt.getShipObjectManagingPos(level, pos) != null) continue;
                BlockState existing = level.getBlockState(pos);
                if (existing.isAir()) continue;
                level.setBlock(pos, TunnelPalette.AIR, Block.UPDATE_CLIENTS);
            }
        }

        // 3. Walls: full-height stone-brick columns just outside the airspace.
        for (int y = tg.floorY(); y <= tg.ceilingY(); y++) {
            setIfNeeded(level, pos, worldX, y, tg.wallMinZ(), TunnelPalette.WALL);
            setIfNeeded(level, pos, worldX, y, tg.wallMaxZ(), TunnelPalette.WALL);
        }

        // 4. Ceiling: 13-wide stone-brick row covers the airspace + wall tops.
        for (int z = tg.wallMinZ(); z <= tg.wallMaxZ(); z++) {
            setIfNeeded(level, pos, worldX, tg.ceilingY(), z, TunnelPalette.CEILING);
        }
    }

    /**
     * Stepped portal — 4 tiers of stone-brick stacked above the tunnel ceiling,
     * narrowing by 1 block each side per level, with stone-brick stairs at the
     * outer edge of each tier smoothing the transition to the tier below.
     */
    private static void placePortalPyramid(ServerLevel level, int worldX, TunnelGeometry tg) {
        int centerZ = tg.centerZ();
        int baseY = tg.ceilingY() + 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int tier = 0; tier < PYRAMID_HALF_WIDTHS.length; tier++) {
            int y = baseY + tier;
            int hw = PYRAMID_HALF_WIDTHS[tier];
            // Core stone-brick row.
            for (int z = centerZ - hw; z <= centerZ + hw; z++) {
                setIfNeeded(level, pos, worldX, y, z, TunnelPalette.PYRAMID);
            }
            // Stair-smoothed outer edges — ramp faces inward (toward the apex).
            placeStair(level, pos, worldX, y, centerZ - hw - 1, Direction.SOUTH);
            placeStair(level, pos, worldX, y, centerZ + hw + 1, Direction.NORTH);
        }
    }

    private static void setIfNeeded(ServerLevel level, BlockPos.MutableBlockPos pos,
                                    int x, int y, int z, BlockState state) {
        pos.set(x, y, z);
        if (VSGameUtilsKt.getShipObjectManagingPos(level, pos) != null) return;
        BlockState existing = level.getBlockState(pos);
        if (existing.is(state.getBlock())) return;
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    private static void placeStair(ServerLevel level, BlockPos.MutableBlockPos pos,
                                   int x, int y, int z, Direction facing) {
        pos.set(x, y, z);
        if (VSGameUtilsKt.getShipObjectManagingPos(level, pos) != null) return;
        BlockState existing = level.getBlockState(pos);
        if (existing.is(TunnelPalette.STAIRS)) return;
        BlockState state = TunnelPalette.STAIRS.defaultBlockState()
            .setValue(StairBlock.FACING, facing);
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    /**
     * Drain up to {@link #CHUNKS_PER_SCAN_BUDGET} chunks of work per call —
     * first the pending queue populated by {@code TunnelChunkEvents}, then a
     * sweep of the view-distance corridor on X × ±{@link #Z_CHUNK_MARGIN} on Z.
     */
    public static void fillRenderDistance(ServerLevel level, ServerShip ship, TrainTransformProvider provider) {
        TrackGeometry g = provider.getTrackGeometry();
        if (g == null) return;

        Set<Long> filled = provider.getTunnelFilledChunks();
        Set<Long> pending = provider.getPendingTunnelChunks();
        int budget = CHUNKS_PER_SCAN_BUDGET;
        int drainedFromPending = 0;

        if (!pending.isEmpty()) {
            Iterator<Long> it = pending.iterator();
            while (budget > 0 && it.hasNext()) {
                long key = it.next();
                it.remove();
                int cx = ChunkPos.getX(key);
                int cz = ChunkPos.getZ(key);
                if (filled.contains(key)) continue;
                if (TrackGenerator.isShipyardChunk(cx, cz)) continue;
                if (!level.getChunkSource().hasChunk(cx, cz)) continue;
                ensureTunnelForChunk(level, cx, cz, g, filled);
                budget--;
                drainedFromPending++;
            }
        }

        int scanned = 0;
        if (budget > 0) {
            int viewDistance = level.getServer().getPlayerList().getViewDistance();
            if (viewDistance <= 0) viewDistance = 10;

            Vector3dc shipWorldPos = ship.getTransform().getPosition();
            int centerCx = (int) Math.floor(shipWorldPos.x()) >> 4;
            int centerCz = g.trackCenterZ() >> 4;

            for (int cz = centerCz - Z_CHUNK_MARGIN; cz <= centerCz + Z_CHUNK_MARGIN && budget > 0; cz++) {
                for (int cx = centerCx - viewDistance; cx <= centerCx + viewDistance && budget > 0; cx++) {
                    if (TrackGenerator.isShipyardChunk(cx, cz)) continue;
                    long key = ChunkPos.asLong(cx, cz);
                    if (filled.contains(key)) continue;
                    if (!level.getChunkSource().hasChunk(cx, cz)) continue;
                    ensureTunnelForChunk(level, cx, cz, g, filled);
                    budget--;
                    scanned++;
                }
            }
        }

        if (budget < CHUNKS_PER_SCAN_BUDGET && LOGGER.isDebugEnabled()) {
            LOGGER.debug("[DungeonTrain] fillTunnelRenderDistance ship={} drained={} (pending={} scan={}) filled.size={} pending.size={}",
                ship.getId(), CHUNKS_PER_SCAN_BUDGET - budget, drainedFromPending, scanned,
                filled.size(), pending.size());
        }
    }
}
