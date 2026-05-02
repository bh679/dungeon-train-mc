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
 * Worldgen-only tunnel placement. Two-pass per chunk: Pass A qualifies each
 * of the 16 X columns as underground (plus cross-chunk terrain probes at
 * chunkMinX-1 / chunkMaxX+1 to detect run boundaries accurately); Pass B
 * walks those qualifications and stamps {@link TunnelTemplate#LENGTH}-col
 * section NBTs (interior) or portal NBTs (run boundaries that pass
 * {@link #airAboveOk}). Pyramid facing outward, connecting side flush against
 * the run interior. Adjacent stamps tile flush along X.
 *
 * <p>Short-tunnel rule: portals are skipped on tunnel runs that are
 * "fully internal" to one chunk (both endpoints inside the chunk OR at
 * chunk boundaries that don't extend cross-chunk). Such runs are at most
 * 16 columns long — too short to fit two non-overlapping 10-col portal
 * stamps.</p>
 *
 * <p>Extension retry: when the air-above check fails at a run boundary,
 * the tunnel is extended by one section (10 cols) into the open-end
 * direction and the check is retried at the new boundary. Up to
 * {@link #MAX_PORTAL_EXTENSIONS} retries; the chain stops early if it
 * would exceed the worldgen 3×3 chunk write window. Pre-scanned: no
 * section is committed until a portal placement succeeds further out.</p>
 *
 * <p>Falls back to {@link LegacyTunnelPaint#paintTunnelColumnWorldgen} when
 * no section NBT is registered (so the corridor stays passable on a fresh
 * install) or when the corridor is too wide for the 3×3 worldgen window.
 * Wide corridors don't get portal facades today (legacy paint has no
 * pyramid).</p>
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

    /**
     * Maximum number of 10-col tunnel-section extensions to try when the
     * air-above check fails at a run boundary. Each extension moves the
     * candidate portal location {@link TunnelTemplate#LENGTH} blocks
     * further into the open-end direction. The chain stops earlier if
     * the next candidate would exceed the worldgen 3×3 chunk write window.
     */
    private static final int MAX_PORTAL_EXTENSIONS = 3;

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
        // centerZ that would otherwise break it. Within Pass A, ext also
        // upgrades to true once the first column in this chunk qualifies,
        // so subsequent qualification probes get the lenient check.
        boolean ext = TUNNELED_CHUNKS.contains(ChunkPos.asLong(chunkX - 1, chunkZ))
                   || TUNNELED_CHUNKS.contains(ChunkPos.asLong(chunkX + 1, chunkZ));

        // Pass A — qualification scan. Indexed (worldX - chunkMinX). Drives
        // both stamping and entrance/exit boundary detection.
        boolean[] qualified = new boolean[16];
        boolean anyQualified = false;
        for (int dx = 0; dx < 16; dx++) {
            int worldX = chunkMinX + dx;
            boolean q = isColumnUndergroundWorldgen(level, worldX, tg, ext);
            qualified[dx] = q;
            if (q) {
                anyQualified = true;
                ext = true;
            }
        }
        if (!anyQualified) return;

        // Cross-chunk terrain probes — read the immediate-neighbour columns
        // (in next/prev chunk) so boundary detection is based on actual
        // terrain qualification rather than a coarse "neighbour chunk has
        // some tunnel" heuristic. Both reads land 1 column outside this
        // chunk, well inside the worldgen 3×3 window.
        boolean prevX_qualified = isColumnUndergroundWorldgen(level, chunkMinX - 1, tg, ext);
        boolean nextX_qualified = isColumnUndergroundWorldgen(level, chunkMaxX + 1, tg, ext);

        int stampLen = TunnelTemplate.LENGTH;
        int lastStampEndX = Integer.MIN_VALUE;  // exclusive lower bound for next non-exit stamp
        int stamped = 0;
        int portals = 0;
        int legacy = 0;

        // Pass B — boundary-aware stamp loop. Per qualified column:
        //  1. Detect entrance / exit using qualified[] + cross-chunk probes.
        //  2. Skip portals on "fully internal" runs (both endpoints inside
        //     this chunk, no cross-chunk extension) — those are at most
        //     16 cols, too short to fit two non-overlapping 10-col stamps.
        //  3. Try exit FIRST — exit portals are allowed to overlap (and
        //     overwrite) prior interior section stamps in the same run.
        //  4. Otherwise, if covered by prior stamp, skip; else try entrance
        //     portal (with backward extensions) or stamp interior section.
        for (int dx = 0; dx < 16; dx++) {
            if (!qualified[dx]) continue;
            int worldX = chunkMinX + dx;

            boolean prevQualified = (dx > 0) ? qualified[dx - 1] : prevX_qualified;
            boolean nextQualified = (dx < 15) ? qualified[dx + 1] : nextX_qualified;
            boolean isEntrance = !prevQualified;
            boolean isExit = !nextQualified;

            // Fully-internal short-tunnel rule — applied at any boundary
            // detection. A run is "fully internal" when both endpoints sit
            // within this chunk (or at chunk boundaries that don't extend
            // cross-chunk). Such runs are at most 16 cols < 20 → skip.
            if (isEntrance || isExit) {
                int runStart = dx;
                int runEnd = dx;
                while (runStart - 1 >= 0 && qualified[runStart - 1]) runStart--;
                while (runEnd + 1 < 16 && qualified[runEnd + 1]) runEnd++;
                boolean runStartsInChunk = runStart > 0 || !prevX_qualified;
                boolean runEndsInChunk = runEnd < 15 || !nextX_qualified;
                if (runStartsInChunk && runEndsInChunk) {
                    isEntrance = false;
                    isExit = false;
                }
            }

            // Exit first — exit portals overlap & overwrite prior section
            // stamps in the same run, so they bypass the lastStampEndX gate.
            if (isExit && canStampSection) {
                int placed = tryPlaceExitPortal(level, serverLevel, tg, worldX, stampOriginZ, chunkMaxX);
                if (placed >= 0) {
                    portals++;
                    lastStampEndX = Math.max(lastStampEndX, placed);
                    continue;
                }
            }

            if (worldX <= lastStampEndX) continue;

            if (canStampSection) {
                if (isEntrance) {
                    int placed = tryPlaceEntrancePortal(level, serverLevel, tg, worldX, stampOriginZ, chunkMinX);
                    if (placed >= 0) {
                        portals++;
                        lastStampEndX = placed;
                        continue;
                    }
                }
                BlockPos origin = new BlockPos(worldX, tg.floorY(), stampOriginZ);
                if (TunnelTemplate.placeSectionAtWorldgen(level, serverLevel, origin)) {
                    stamped++;
                    lastStampEndX = worldX + stampLen - 1;
                    continue;
                }
            }
            // NBT missing OR wide-corridor case — legacy paint keeps the
            // corridor passable. Portals require NBT stamping so wide
            // corridors get no facade today.
            LegacyTunnelPaint.paintTunnelColumnWorldgen(level, worldX, tg);
            legacy++;
        }
        if (stamped > 0 || portals > 0 || legacy > 0) {
            TUNNELED_CHUNKS.add(ChunkPos.asLong(chunkX, chunkZ));
            LOGGER.info("[DungeonTrain] tunnel.cols.worldgen chunk=({},{}) nbt={} portal={} legacy={}",
                chunkX, chunkZ, stamped, portals, legacy);
        }
    }

    /**
     * Try placing an entrance portal anchored at {@code worldX} (the run's
     * first qualified column), retrying with up to
     * {@link #MAX_PORTAL_EXTENSIONS} backward 10-col tunnel-section
     * extensions when {@link #airAboveOk} fails. Pre-scans candidate
     * positions: nothing is committed to the world until a position passes
     * the air-above check.
     *
     * <p>Returns {@code worldX + LENGTH - 1} (the rightmost X covered) on
     * success, or {@code -1} when no portal could be placed (all candidates
     * failed, hit the worldgen 3×3 window boundary, or NBT missing). On
     * success with extensions > 0, also stamps the original boundary's
     * section so the qualified run is continuous between the portal's
     * extended position and the original boundary.</p>
     */
    private static int tryPlaceEntrancePortal(WorldGenLevel level, ServerLevel serverLevel,
            TunnelGeometry tg, int worldX, int stampOriginZ, int chunkMinX) {
        int currentBoundary = worldX;
        int extensions = 0;
        while (!airAboveOk(level, tg, currentBoundary, false)) {
            if (extensions >= MAX_PORTAL_EXTENSIONS) return -1;
            int next = currentBoundary - TunnelTemplate.LENGTH;
            // Stay within the worldgen 3×3 chunk write window on -X.
            if (next < chunkMinX - 16) return -1;
            currentBoundary = next;
            extensions++;
        }
        // Commit: intermediate sections fill the gap between the portal and
        // the original boundary. Portal stamp at currentBoundary covers
        // currentBoundary..currentBoundary+9; intermediates are at
        // worldX - LENGTH * e for e in [1, extensions - 1].
        for (int e = 1; e < extensions; e++) {
            int sectionOriginX = worldX - TunnelTemplate.LENGTH * e;
            BlockPos sectionOrigin = new BlockPos(sectionOriginX, tg.floorY(), stampOriginZ);
            if (!TunnelTemplate.placeSectionAtWorldgen(level, serverLevel, sectionOrigin)) {
                return -1;
            }
        }
        BlockPos portalOrigin = new BlockPos(currentBoundary, tg.floorY(), stampOriginZ);
        if (!TunnelTemplate.placePortalAtWorldgen(level, serverLevel, portalOrigin, false)) {
            return -1;
        }
        // For extensions > 0 the portal stamp doesn't reach the original
        // boundary worldX; stamp the boundary's section so the qualified
        // run has continuous tunnel from the portal forward.
        if (extensions > 0) {
            BlockPos origSection = new BlockPos(worldX, tg.floorY(), stampOriginZ);
            TunnelTemplate.placeSectionAtWorldgen(level, serverLevel, origSection);
        }
        return worldX + TunnelTemplate.LENGTH - 1;
    }

    /**
     * Try placing an exit portal anchored at {@code worldX} (the run's
     * last qualified column), retrying with up to
     * {@link #MAX_PORTAL_EXTENSIONS} forward 10-col tunnel-section
     * extensions when {@link #airAboveOk} fails. Mirrors
     * {@link #tryPlaceEntrancePortal} on +X. The original boundary's
     * tunnel section is already covered by prior interior section stamps
     * (placed earlier in Pass B during interior iteration), so no extra
     * boundary section is stamped here.
     *
     * <p>Returns {@code currentBoundary} (the extended boundary X, where
     * the pyramid lands) on success, or {@code -1} on failure.</p>
     */
    private static int tryPlaceExitPortal(WorldGenLevel level, ServerLevel serverLevel,
            TunnelGeometry tg, int worldX, int stampOriginZ, int chunkMaxX) {
        int currentBoundary = worldX;
        int extensions = 0;
        while (!airAboveOk(level, tg, currentBoundary, true)) {
            if (extensions >= MAX_PORTAL_EXTENSIONS) return -1;
            int next = currentBoundary + TunnelTemplate.LENGTH;
            // Stay within the worldgen 3×3 chunk write window on +X. The
            // section/portal stamp covers up to currentBoundary, so the
            // rightmost write is currentBoundary itself.
            if (next > chunkMaxX + 16) return -1;
            currentBoundary = next;
            extensions++;
        }
        // Intermediate sections: e in [1, extensions - 1], section at
        // origin = worldX + LENGTH * e - (LENGTH - 1) = worldX + 10*e - 9.
        for (int e = 1; e < extensions; e++) {
            int sectionOriginX = worldX + TunnelTemplate.LENGTH * e - (TunnelTemplate.LENGTH - 1);
            BlockPos sectionOrigin = new BlockPos(sectionOriginX, tg.floorY(), stampOriginZ);
            if (!TunnelTemplate.placeSectionAtWorldgen(level, serverLevel, sectionOrigin)) {
                return -1;
            }
        }
        // Exit portal: mirrorX=true puts pyramid at originX + 9, so origin
        // = currentBoundary - 9 lands the pyramid AT currentBoundary.
        BlockPos portalOrigin = new BlockPos(currentBoundary - (TunnelTemplate.LENGTH - 1), tg.floorY(), stampOriginZ);
        if (!TunnelTemplate.placePortalAtWorldgen(level, serverLevel, portalOrigin, true)) {
            return -1;
        }
        return currentBoundary;
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

    /**
     * X-axis offsets for the {@link #airAboveOk} probe span — 3 columns
     * spaced 2 blocks apart, starting one column outside the open-end face
     * and extending inward toward the connecting side. The 3 probes cover
     * a 6-column zone at the open end of the portal.
     *
     * <ul>
     *   <li>{@code mirrorX == false} (entrance, open faces -X):
     *       {@code { -1, +1, +3 }} — outside the -X open end, then 2 cols
     *       inward, then 4 cols inward (toward connecting side at +X).</li>
     *   <li>{@code mirrorX == true} (exit, open faces +X):
     *       {@code { +1, -1, -3 }} — same pattern mirrored.</li>
     * </ul>
     *
     * <p>Pure arithmetic, no level access — extracted so the offset shape
     * can be unit-tested independently of {@link WorldGenLevel}.</p>
     */
    static int[] probeXOffsets(boolean mirrorX) {
        int dir = mirrorX ? -1 : 1;
        return new int[] { -dir, dir, 3 * dir };
    }

    /**
     * Validity gate for portal placement. At each of the 3 X probe columns
     * (see {@link #probeXOffsets}), checks 3 spatial positions:
     * <ul>
     *   <li><b>Top of tunnel</b>: {@code (probeX, ceilingY + 1, centerZ)} —
     *       just above the tunnel ceiling at the corridor centre.</li>
     *   <li><b>Left side</b>: {@code (probeX, midY, wallMinZ)} —
     *       mid-height of the tunnel cross-section, at the left wall.</li>
     *   <li><b>Right side</b>: {@code (probeX, midY, wallMaxZ)} —
     *       mid-height of the tunnel cross-section, at the right wall.</li>
     * </ul>
     * where {@code midY = (floorY + ceilingY) / 2}.
     *
     * <p>All 9 probes (3 X × 3 spatial) must land on non-underground
     * material (air, leaves, plants, water — anything
     * {@link TunnelPalette#isUndergroundMaterial} returns {@code false} for).
     * Any single probe hitting solid underground rock disqualifies the
     * portal — the tunnel just terminates raw at the boundary, OR the
     * caller may attempt an extension and re-probe further out.</p>
     *
     * <p>Intent: a portal facade only makes visual sense where the open
     * end emerges into open space — open above the ceiling AND open to
     * each side at mid-height. Placing one inside continuous solid rock
     * would just be a stone wall pyramid embedded in the mountain.</p>
     *
     * <p>Probe X range stays within ±3 of {@code x}, well inside the
     * worldgen 3×3 decoration window's read envelope when called for
     * any column the chunk's loop touches (or for an extension within the
     * window's bounds).</p>
     */
    private static boolean airAboveOk(WorldGenLevel level, TunnelGeometry tg, int x, boolean mirrorX) {
        int topY = tg.ceilingY() + 1;
        int sideY = (tg.floorY() + tg.ceilingY()) / 2;
        int centerZ = tg.centerZ();
        int wallMinZ = tg.wallMinZ();
        int wallMaxZ = tg.wallMaxZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int xOffset : probeXOffsets(mirrorX)) {
            int probeX = x + xOffset;
            pos.set(probeX, topY, centerZ);
            if (TunnelPalette.isUndergroundMaterial(level.getBlockState(pos))) return false;
            pos.set(probeX, sideY, wallMinZ);
            if (TunnelPalette.isUndergroundMaterial(level.getBlockState(pos))) return false;
            pos.set(probeX, sideY, wallMaxZ);
            if (TunnelPalette.isUndergroundMaterial(level.getBlockState(pos))) return false;
        }
        return true;
    }
}
