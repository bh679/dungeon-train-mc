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
 * wherever the rock overhead is thick enough, plus an open cutting on the
 * 20 X columns either side of each tunnel so the pyramid portals are always
 * framed against sky. Mirrors {@link TrackGenerator}: chunk-driven deferred
 * paint with a per-train cache, one chunk per scan.
 *
 * <p>Per <b>tunnel-qualified</b> X column: stone-brick floor (11 wide),
 * 11-wide × 8-tall rectangular airspace, two full-height stone-brick walls,
 * and a stepped arched stone-brick roof that rises 4 rows above the wall
 * tops in a 4-tier profile mirroring the entrance pyramid.</p>
 *
 * <p>Per <b>approach-qualified</b> X column (not tunnel-qualified, but
 * within {@link #APPROACH_MARGIN} blocks of one that is): stone-brick floor
 * (11 wide) + 13-wide × 15-tall air cutting up to the apex height, leaving
 * sky visible above the corridor.</p>
 *
 * <p>At tunnel-qualified columns that border a non-tunnel neighbour (either
 * direction), a 4-tier stepped stone-brick pyramid is placed on top of the
 * arch — overriding the arch's central air with a solid facade so the
 * portal reads as a single vaulted entrance / exit.</p>
 */
public final class TunnelGenerator {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 13-wide tunnel needs ±2 chunks on Z to be covered by the view-distance sweep. */
    private static final int Z_CHUNK_MARGIN = 2;

    /** Max previously-unprocessed chunks to fill per periodic scan — same budget as track fill. */
    private static final int CHUNKS_PER_SCAN_BUDGET = 1;

    /** Clearing radius around each tunnel region — 20 blocks before and after each portal. */
    private static final int APPROACH_MARGIN = 20;

    /** Half-widths of each pyramid tier. Widths are {11, 9, 7, 5}. */
    private static final int[] PYRAMID_HALF_WIDTHS = { 5, 4, 3, 2 };

    /** Number of tiers in the arched interior roof (rising above {@code ceilingY}). */
    private static final int ARCH_TIERS = 3;

    /** Distance between lamp stations along X. Every Nth world X gets a pair of wall lamps. */
    private static final int LAMP_SPACING = 10;

    /** Lamp Y sits this many blocks above the rail — walking eye height inside the tunnel. */
    private static final int LAMP_Y_OFFSET_FROM_RAIL = 2;

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

        // Precompute qualification for the chunk's 16 columns plus
        // APPROACH_MARGIN on each side — approach detection needs to see
        // 20 X past the chunk's boundary. Each column runs one 6-sample
        // underground check; total ~350 block reads per chunk.
        int baseX = chunkMinX - APPROACH_MARGIN;
        int size = 16 + 2 * APPROACH_MARGIN;
        boolean[] qualified = new boolean[size];
        for (int i = 0; i < size; i++) {
            qualified[i] = isColumnUnderground(level, baseX + i, tg);
        }

        for (int worldX = chunkMinX; worldX <= chunkMaxX; worldX++) {
            int idx = worldX - baseX;
            boolean tunnel = qualified[idx];
            if (tunnel) {
                paintTunnelColumn(level, worldX, tg);
                // Portal = tunnel-qualified column bordering a non-tunnel
                // column on either side. Single-column tunnels (unusual but
                // legal) get a pyramid too.
                boolean prev = idx > 0 && qualified[idx - 1];
                boolean next = idx + 1 < size && qualified[idx + 1];
                if (!prev || !next) {
                    placePortalPyramid(level, worldX, tg);
                }
            } else if (isNearTunnel(qualified, idx)) {
                paintApproachColumn(level, worldX, tg);
            }
        }

        tunnelFilledChunks.add(chunkKey);
    }

    /**
     * Six-sample underground check: at y = ceilingY+1 and ceilingY+2, for
     * three Z positions spanning the track corridor (min, center, max), every
     * sampled block must be a natural underground material. Any air, water,
     * plant, or wood disqualifies the column.
     *
     * <p>Returns {@code false} if the chunk holding these samples isn't
     * loaded — avoids the force-load cost of {@code getBlockState} on an
     * unloaded chunk. The chunk will re-enqueue itself on load via
     * {@code TunnelChunkEvents} and pick up this column's status then.</p>
     */
    static boolean isColumnUnderground(ServerLevel level, int worldX, TunnelGeometry tg) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        pos.set(worldX, tg.ceilingY() + 1, tg.centerZ());
        if (!level.hasChunkAt(pos)) return false;
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

    /** True if any column within {@link #APPROACH_MARGIN} of {@code idx} is tunnel-qualified. */
    private static boolean isNearTunnel(boolean[] qualified, int idx) {
        for (int dx = 1; dx <= APPROACH_MARGIN; dx++) {
            int left = idx - dx;
            int right = idx + dx;
            if (left >= 0 && qualified[left]) return true;
            if (right < qualified.length && qualified[right]) return true;
        }
        return false;
    }

    private static void paintTunnelColumn(ServerLevel level, int worldX, TunnelGeometry tg) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // 1. Extend floor outward. [trackZMin..trackZMax] is the existing stone-brick
        //    bed (from TrackGenerator); tunnel floor needs 11-wide coverage.
        for (int z = tg.airMinZ(); z <= tg.airMaxZ(); z++) {
            if (z >= tg.trackZMin() && z <= tg.trackZMax()) continue;
            setIfNeeded(level, pos, worldX, tg.floorY(), z, TunnelPalette.FLOOR);
        }

        // 2. Airspace: 11 wide × 9 tall between floor and ceiling (now including
        //    y=ceilingY since the flat ceiling is gone — replaced by the arch).
        //    Preserve rails at y=railY, z=railZMin|railZMax (placed by TrackGenerator).
        int airYMin = tg.railY();
        int airYMax = tg.ceilingY();
        for (int y = airYMin; y <= airYMax; y++) {
            for (int z = tg.airMinZ(); z <= tg.airMaxZ(); z++) {
                if (y == tg.railY() && (z == tg.railZMin() || z == tg.railZMax())) continue;
                setAirIfNeeded(level, pos, worldX, y, z);
            }
        }

        // 3. Walls: full-height stone-brick columns just outside the airspace.
        //    Every LAMP_SPACING X blocks a pair of sea lanterns replaces the wall
        //    stone-brick at walking eye height (railY + LAMP_Y_OFFSET_FROM_RAIL).
        int lampY = tg.railY() + LAMP_Y_OFFSET_FROM_RAIL;
        boolean isLampColumn = Math.floorMod(worldX, LAMP_SPACING) == 0;
        for (int y = tg.floorY(); y <= tg.ceilingY(); y++) {
            BlockState sideBlock = (isLampColumn && y == lampY) ? TunnelPalette.SEA_LANTERN : TunnelPalette.WALL;
            setIfNeeded(level, pos, worldX, y, tg.wallMinZ(), sideBlock);
            setIfNeeded(level, pos, worldX, y, tg.wallMaxZ(), sideBlock);
        }

        // 4. Arched interior roof — stepped pyramid profile rising 4 rows above wall tops.
        paintArchedRoof(level, worldX, tg);
    }

    /**
     * Stepped arch rising above the tunnel's wall tops, mirroring the portal
     * pyramid profile. Tier N sits at y = ceilingY + N, with a 1-block inset
     * per tier from the walls, stone-brick stairs smoothing each step, and
     * a flat 5-wide stone-brick cap at the apex.
     */
    private static void paintArchedRoof(ServerLevel level, int worldX, TunnelGeometry tg) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int tier = 1; tier <= ARCH_TIERS; tier++) {
            int y = tg.ceilingY() + tier;
            int stoneZLo = tg.wallMinZ() + tier;        // inner edge of this tier's stone row
            int stoneZHi = tg.wallMaxZ() - tier;

            // Stone-brick roof edges — two single blocks spanning the tier width.
            setIfNeeded(level, pos, worldX, y, stoneZLo, TunnelPalette.WALL);
            setIfNeeded(level, pos, worldX, y, stoneZHi, TunnelPalette.WALL);

            // Smoothing stairs — one block outside stoneZLo/stoneZHi, facing inward
            // (toward centerZ) so the high half of the stair sits flush with the
            // stone-brick step on the inside edge.
            placeStair(level, pos, worldX, y, stoneZLo - 1, Direction.SOUTH);
            placeStair(level, pos, worldX, y, stoneZHi + 1, Direction.NORTH);

            // Air fill inside the tier.
            for (int z = stoneZLo + 1; z <= stoneZHi - 1; z++) {
                setAirIfNeeded(level, pos, worldX, y, z);
            }
        }

        // Apex cap: flat stone-brick closing the 5-wide air column at the peak.
        int apexY = tg.ceilingY() + ARCH_TIERS + 1;
        int apexZLo = tg.wallMinZ() + ARCH_TIERS + 1;
        int apexZHi = tg.wallMaxZ() - ARCH_TIERS - 1;
        for (int z = apexZLo; z <= apexZHi; z++) {
            setIfNeeded(level, pos, worldX, apexY, z, TunnelPalette.CEILING);
        }
    }

    /**
     * Open cutting — floor extension plus a 13-wide × 15-tall air column, no
     * walls / no roof. Visually "the train has carved an uncovered trench
     * into the hillside right up to the portal face".
     */
    private static void paintApproachColumn(ServerLevel level, int worldX, TunnelGeometry tg) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // 1. Floor extension — 11-wide stone-brick at y=floorY, excluding the
        //    5-wide track bed which TrackGenerator owns.
        for (int z = tg.airMinZ(); z <= tg.airMaxZ(); z++) {
            if (z >= tg.trackZMin() && z <= tg.trackZMax()) continue;
            setIfNeeded(level, pos, worldX, tg.floorY(), z, TunnelPalette.FLOOR);
        }

        // 2. Air cutting — everything in the tunnel's external cross-section
        //    from floorY+1 up to the arch apex, across the 13-wide wall span.
        //    Preserve rails.
        int topY = tg.ceilingY() + ARCH_TIERS + 1;
        for (int y = tg.floorY() + 1; y <= topY; y++) {
            for (int z = tg.wallMinZ(); z <= tg.wallMaxZ(); z++) {
                if (y == tg.railY() && (z == tg.railZMin() || z == tg.railZMax())) continue;
                setAirIfNeeded(level, pos, worldX, y, z);
            }
        }
    }

    /**
     * Stepped portal — 4 tiers of stone-brick stacked above the arched roof,
     * overriding the arch's central air with a solid facade at the boundary
     * between tunnel and non-tunnel territory. Stair-smoothed outer edges
     * match the arched-roof stair convention (facing inward, toward centerZ).
     */
    private static void placePortalPyramid(ServerLevel level, int worldX, TunnelGeometry tg) {
        int centerZ = tg.centerZ();
        int baseY = tg.ceilingY() + 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int tier = 0; tier < PYRAMID_HALF_WIDTHS.length; tier++) {
            int y = baseY + tier;
            int hw = PYRAMID_HALF_WIDTHS[tier];
            // Core stone-brick row — solid, overrides the arch's air at this X.
            for (int z = centerZ - hw; z <= centerZ + hw; z++) {
                setIfNeeded(level, pos, worldX, y, z, TunnelPalette.PYRAMID);
            }
            // Stairs at outer edges of this tier, facing inward.
            placeStair(level, pos, worldX, y, centerZ - hw - 1, Direction.SOUTH);
            placeStair(level, pos, worldX, y, centerZ + hw + 1, Direction.NORTH);
        }
    }

    private static void setIfNeeded(ServerLevel level, BlockPos.MutableBlockPos pos,
                                    int x, int y, int z, BlockState state) {
        pos.set(x, y, z);
        if (!level.hasChunkAt(pos)) return;
        if (VSGameUtilsKt.getShipObjectManagingPos(level, pos) != null) return;
        BlockState existing = level.getBlockState(pos);
        if (existing.is(state.getBlock())) return;
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    private static void setAirIfNeeded(ServerLevel level, BlockPos.MutableBlockPos pos,
                                       int x, int y, int z) {
        pos.set(x, y, z);
        if (!level.hasChunkAt(pos)) return;
        if (VSGameUtilsKt.getShipObjectManagingPos(level, pos) != null) return;
        BlockState existing = level.getBlockState(pos);
        if (existing.isAir()) return;
        level.setBlock(pos, TunnelPalette.AIR, Block.UPDATE_CLIENTS);
    }

    private static void placeStair(ServerLevel level, BlockPos.MutableBlockPos pos,
                                   int x, int y, int z, Direction facing) {
        pos.set(x, y, z);
        if (!level.hasChunkAt(pos)) return;
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
