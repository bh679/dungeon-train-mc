package games.brennan.dungeontrain.tunnel;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Worldgen-only tunnel placement. Single-phase: scan each X column in the
 * chunk; at the first qualified column not covered by the previous stamp,
 * place the full {@link TunnelTemplate#LENGTH}-col section NBT and skip the
 * next {@code LENGTH-1} columns. Adjacent stamps tile flush — each NBT
 * shows its full 10-column content rather than getting "stretched" by
 * overlap.
 *
 * <p>Falls back to {@link LegacyTunnelPaint#paintTunnelColumnWorldgen} when
 * no section NBT is registered (so the corridor stays passable on a fresh
 * install) or when the corridor is too wide for the 3×3 worldgen window.</p>
 *
 * <p>No portal stamps, no run detection, no runtime drain, no NBT-length
 * fit checks. Cross-chunk alignment is per-chunk relative (each chunk
 * starts its tile chain at its own leftmost qualified column), which can
 * leave a visible seam at chunk boundaries inside long tunnels — accepted
 * trade-off vs. legacy gaps with absolute alignment.</p>
 */
public final class TunnelGenerator {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Per-chunk "this chunk had ≥ 1 tunnel column placed" flag, used to
     * propagate the more-lenient {@code ext} qualification mode to
     * neighbouring chunks along the corridor (X-axis). When this chunk is
     * processed, if either {@code (chunkX±1, chunkZ)} is in this set the
     * scan starts in extended mode — a single soft spot at {@code centerZ}
     * (cave entrance, river bed, etc.) won't terminate an otherwise
     * continuous tunnel.
     *
     * <p>In-memory only; on server restart the flag resets. Chunks
     * generated post-restart at the boundary may show a slight seam where
     * the tunnel doesn't start as early as it would have. Accepted
     * trade-off vs. {@link net.minecraft.world.level.saveddata.SavedData}
     * synchronization across worldgen worker threads.</p>
     */
    private static final Set<Long> TUNNELED_CHUNKS = ConcurrentHashMap.newKeySet();

    private TunnelGenerator() {}

    /**
     * Worldgen-time tunnel placement. For each X column in this chunk that
     * qualifies as underground (probe at {@code (worldX, ceilingY+1, centerZ)},
     * see {@link #isColumnUndergroundWorldgen}), stamp the full section NBT at
     * that column via {@link TunnelTemplate#placeSectionAtWorldgen}. On NBT
     * miss (or when the tunnel Z extent exceeds the 3×3 worldgen window for
     * very wide corridors), fall back to
     * {@link LegacyTunnelPaint#paintTunnelColumnWorldgen}.
     *
     * <p>Tunnel continuity uses an {@code ext} flag tracked in
     * {@link #TUNNELED_CHUNKS}: a chunk inherits {@code ext=true} on entry if
     * either X-neighbour was tunneled, and within the chunk {@code ext} also
     * upgrades to true once the first column gets a tunnel placement. In
     * extended mode the qualification probe widens to a 7-block Z scan,
     * tolerating soft spots at {@code centerZ}.</p>
     */
    public static void placeTunnelSpaceAtWorldgen(
        WorldGenLevel level, ServerLevel serverLevel,
        int chunkX, int chunkZ, TrackGeometry g
    ) {
        if (TrackGenerator.isShipyardChunk(chunkX, chunkZ)) return;

        TunnelGeometry tg = TunnelGeometry.from(g);

        int chunkMinX = chunkX << 4;
        int chunkMaxX = chunkMinX + 15;
        int chunkMinZ = chunkZ << 4;
        int chunkMaxZ = chunkMinZ + 15;

        if (chunkMaxZ < tg.trackZMin() || chunkMinZ > tg.trackZMax()) return;

        // 3×3-window fit on Z: stamp origin is offset +1 on Z (template is
        // authored 1 block toward +Z relative to the corridor walls), so
        // the stamp spans tg.wallMinZ+1..tg.wallMaxZ+1 (13 wide).
        // Worldgen can write to chunks within ±1 of this chunk on Z. Wider
        // corridors (width > 16) exceed that envelope and fall back to
        // legacy paint per-column for the whole chunk.
        int stampOriginZ = tg.wallMinZ() + 1;
        int stampMinChunkZ = stampOriginZ >> 4;
        int stampMaxChunkZ = (stampOriginZ + TunnelTemplate.WIDTH - 1) >> 4;
        boolean canStampSection = stampMinChunkZ >= chunkZ - 1 && stampMaxChunkZ <= chunkZ + 1;

        // ext starts true if either X-neighbour at this chunkZ has already
        // been tunneled — lets a continuous tunnel cross a soft spot at
        // centerZ that would otherwise break it. Within the loop, ext also
        // upgrades to true once the first column in this chunk gets a
        // tunnel placement, so subsequent columns get the lenient check.
        boolean ext = TUNNELED_CHUNKS.contains(ChunkPos.asLong(chunkX - 1, chunkZ))
                   || TUNNELED_CHUNKS.contains(ChunkPos.asLong(chunkX + 1, chunkZ));

        // Chunk-center fast reject. Uses the same ext mode the loop will
        // start with: if even the chunk-center column doesn't qualify,
        // nothing in this chunk will either.
        if (!isColumnUndergroundWorldgen(level, chunkMinX + 8, tg, ext)) return;

        int stampLen = TunnelTemplate.LENGTH;
        int lastStampEndX = Integer.MIN_VALUE;  // exclusive lower bound for next stamp
        int stamped = 0;
        int legacy = 0;
        for (int worldX = chunkMinX; worldX <= chunkMaxX; worldX++) {
            if (!isColumnUndergroundWorldgen(level, worldX, tg, ext)) continue;
            if (worldX <= lastStampEndX) continue;  // covered by previous stamp's footprint
            if (canStampSection) {
                // Stamp origin offset +1 on Z — the NBT template content
                // is authored 1 block toward +Z relative to the corridor
                // walls, so origin Z = wallMinZ + 1 lands the template
                // visually centred on the corridor.
                BlockPos origin = new BlockPos(worldX, tg.floorY(), stampOriginZ);
                if (TunnelTemplate.placeSectionAtWorldgen(level, serverLevel, origin)) {
                    stamped++;
                    lastStampEndX = worldX + stampLen - 1;
                    ext = true;
                    continue;
                }
            }
            // NBT missing OR wide-corridor case — legacy paint keeps the
            // corridor passable.
            LegacyTunnelPaint.paintTunnelColumnWorldgen(level, worldX, tg);
            legacy++;
            ext = true;
        }
        if (stamped > 0 || legacy > 0) {
            TUNNELED_CHUNKS.add(ChunkPos.asLong(chunkX, chunkZ));
            LOGGER.info("[DungeonTrain] tunnel.cols.worldgen chunk=({},{}) nbt={} legacy={}",
                chunkX, chunkZ, stamped, legacy);
        }
    }

    /**
     * Single-column underground qualification at {@code (worldX, ceilingY+1,
     * centerZ)} via {@link WorldGenLevel}. Skips the {@code level.hasChunkAt}
     * gate (worldgen guarantees the current chunk is loaded; ±1 chunk reads
     * land in the standard 3×3 decoration window).
     *
     * <p>Two modes:</p>
     * <ul>
     *   <li><b>Default ({@code ext=false})</b>: single probe at
     *       {@code (worldX, ceilingY+1, centerZ)}. A miss disqualifies the
     *       column.</li>
     *   <li><b>Extended ({@code ext=true})</b>: only enabled once a tunnel
     *       is "active" upstream (within the same chunk after a stamp, or
     *       on entry from a neighbour chunk that was tunneled). On a center
     *       miss, scan a coarse 3D grid: 4 Y levels going down from
     *       {@code probeY}, with Z patterns alternating between
     *       <ul>
     *         <li>Pattern A ({@code dy} even): {@code dz ∈ {-3,-1,0,+1,+3}}</li>
     *         <li>Pattern B ({@code dy} odd):  {@code dz ∈ {-2,0,+2}}</li>
     *       </ul>
     *       Tolerates a soft spot at {@code centerZ} (e.g. cave entrance,
     *       river bed) so it doesn't terminate an otherwise continuous
     *       tunnel. Scanning down 4 Y levels keeps an ongoing tunnel from
     *       breaking just because the prospective ceiling clips a thin
     *       overhead air pocket while the lower body of the column is
     *       still buried.</li>
     * </ul>
     */
    static boolean isColumnUndergroundWorldgen(WorldGenLevel level, int worldX, TunnelGeometry tg, boolean ext) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int probeY = tg.ceilingY() + 1;
        int centerZ = tg.centerZ();
        pos.set(worldX, probeY, centerZ);
        if (TunnelPalette.isUndergroundMaterial(level.getBlockState(pos))) return true;
        if (!ext) return false;
        int[] patternA = { -3, -1, 0, 1, 3 };
        int[] patternB = { -2, 0, 2 };
        for (int dy = 0; dy < 4; dy++) {
            int y = probeY - dy;
            int[] pattern = (dy % 2 == 0) ? patternA : patternB;
            for (int dz : pattern) {
                if (dy == 0 && dz == 0) continue; // already checked in cheap path
                pos.set(worldX, y, centerZ + dz);
                if (TunnelPalette.isUndergroundMaterial(level.getBlockState(pos))) return true;
            }
        }
        return false;
    }

    /**
     * {@link ServerLevel} variant of {@link #isColumnUndergroundWorldgen}.
     * Used by login-spawn placement
     * ({@code games.brennan.dungeontrain.event.PlayerJoinEvents}) to find
     * the first non-tunnel column for the player's safe-spawn fallback.
     * Independent of worldgen — caller is responsible for loading the chunk
     * before calling. Returns {@code false} for unloaded chunks.
     */
    public static boolean isColumnUnderground(ServerLevel level, int worldX, TunnelGeometry tg) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        pos.set(worldX, tg.ceilingY() + 1, tg.centerZ());
        if (!level.hasChunkAt(pos)) return false;
        return TunnelPalette.isUndergroundMaterial(level.getBlockState(pos));
    }
}
