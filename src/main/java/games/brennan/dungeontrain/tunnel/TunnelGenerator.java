package games.brennan.dungeontrain.tunnel;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.ServerShip;

import java.util.Iterator;
import java.util.Set;

/**
 * Dispatcher that paints stone-brick tunnels around the train's path. Detection
 * + qualification happen here; the actual block placement is delegated to
 * {@link TunnelTemplate} (NBT-backed, editable via
 * {@code /dungeontrain editor enter tunnel_section|tunnel_portal}) or falls
 * back to {@link LegacyTunnelPaint} when no template has been saved.
 *
 * <p>Per-chunk work stays the same: a 16+2×APPROACH_MARGIN column underground
 * sample, then for each X column owned by the chunk we either stamp a
 * {@code tunnel_section} / {@code tunnel_portal} aligned to
 * {@link LegacyTunnelPaint#LAMP_SPACING}, or fall back to per-column procedural
 * paint when the section spans mixed qualification or partially-unloaded
 * chunks.</p>
 *
 * <p>Approach-cutting (20-block open trench before / after each tunnel) stays
 * procedural — the same {@link LegacyTunnelPaint#paintApproachColumn} logic as
 * pre-refactor.</p>
 */
public final class TunnelGenerator {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 13-wide tunnel needs ±2 chunks on Z to be covered by the view-distance sweep. */
    private static final int Z_CHUNK_MARGIN = 2;

    /** Max previously-unprocessed chunks to fill per periodic scan — same budget as track fill. */
    private static final int CHUNKS_PER_SCAN_BUDGET = 1;

    /** Clearing radius around each tunnel region — 20 blocks before and after each portal. */
    private static final int APPROACH_MARGIN = 20;

    private TunnelGenerator() {}

    private enum SectionRole {
        /** Middle section of a run — both neighbours qualified. */
        SECTION,
        /** Leftmost section of a run — left neighbour not qualified. Portal opens −X. */
        PORTAL_ENTRANCE,
        /** Rightmost section of a run — right neighbour not qualified. Portal opens +X. */
        PORTAL_EXIT
    }

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

        int spacing = LegacyTunnelPaint.LAMP_SPACING;
        for (int worldX = chunkMinX; worldX <= chunkMaxX; worldX++) {
            int idx = worldX - baseX;
            int sectionStart = worldX - Math.floorMod(worldX, spacing);
            int sectionIdx = sectionStart - baseX;
            SectionRole role = classifySection(qualified, sectionIdx, spacing);

            if (role != null && sectionChunksLoaded(level, sectionStart, spacing, tg)) {
                // Stamp once per chunk the section overlaps, at the first
                // column of the section within THIS chunk. Cross-chunk
                // sections (e.g. origin sx=10 spanning chunks [0..15] and
                // [16..31]) thus get re-stamped from the neighbour chunk —
                // idempotent at the block level, and it recovers the case
                // where the origin chunk painted procedurally because its
                // neighbour wasn't yet loaded at processing time.
                int stampTrigger = Math.max(sectionStart, chunkMinX);
                if (worldX == stampTrigger) {
                    stampByRole(level, sectionStart, tg, role);
                }
                continue; // column is covered by the stamp
            }

            // Sections owned by a prior chunk that isn't stampable now fall
            // through to the prior chunk's own fallback — don't repaint here.
            if (sectionStart < chunkMinX) continue;

            // Fallback: procedural per-column paint (PARTIAL section, single-
            // section tunnel run, or overlap chunks not all loaded).
            if (qualified[idx]) {
                LegacyTunnelPaint.paintTunnelColumn(level, worldX, tg);
                boolean prev = idx > 0 && qualified[idx - 1];
                boolean next = idx + 1 < qualified.length && qualified[idx + 1];
                if (!prev || !next) {
                    LegacyTunnelPaint.placePortalPyramid(level, worldX, tg);
                }
            } else if (isNearTunnel(qualified, idx)) {
                LegacyTunnelPaint.paintApproachColumn(level, worldX, tg);
            }
        }

        tunnelFilledChunks.add(chunkKey);
    }

    /**
     * Classify the {@code spacing}-wide section starting at {@code sectionIdx}
     * in the {@code qualified} array. Returns {@code null} when the section
     * is not fully qualified OR when it's a single-section run (both
     * neighbours not qualified) — both cases fall back to per-column paint.
     */
    private static SectionRole classifySection(boolean[] qualified, int sectionIdx, int spacing) {
        if (sectionIdx < 0 || sectionIdx + spacing > qualified.length) return null;
        for (int i = 0; i < spacing; i++) {
            if (!qualified[sectionIdx + i]) return null;
        }
        boolean leftQual = sectionIdx > 0 && qualified[sectionIdx - 1];
        boolean rightQual = sectionIdx + spacing < qualified.length && qualified[sectionIdx + spacing];
        if (leftQual && rightQual) return SectionRole.SECTION;
        if (!leftQual && rightQual) return SectionRole.PORTAL_ENTRANCE;
        if (leftQual) return SectionRole.PORTAL_EXIT;
        // Single-section run (!leftQual && !rightQual) — fall back to per-column.
        return null;
    }

    private static void stampByRole(ServerLevel level, int sectionStart, TunnelGeometry tg, SectionRole role) {
        BlockPos origin = new BlockPos(sectionStart, tg.floorY(), tg.wallMinZ());
        switch (role) {
            case SECTION -> TunnelTemplate.placeSectionAt(level, origin);
            case PORTAL_ENTRANCE -> TunnelTemplate.placePortalAt(level, origin, false);
            case PORTAL_EXIT -> TunnelTemplate.placePortalAt(level, origin, true);
        }
    }

    /**
     * Check that every chunk the 10×14×13 section footprint overlaps is
     * currently loaded. A section spans chunks on both X (when it crosses a
     * 16-block boundary) and Z (the 13-wide wall span always straddles at
     * least 2 chunks on Z).
     */
    private static boolean sectionChunksLoaded(ServerLevel level, int sectionStart, int lengthX, TunnelGeometry tg) {
        int minCx = sectionStart >> 4;
        int maxCx = (sectionStart + lengthX - 1) >> 4;
        int minCz = tg.wallMinZ() >> 4;
        int maxCz = tg.wallMaxZ() >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!level.getChunkSource().hasChunk(cx, cz)) return false;
            }
        }
        return true;
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
