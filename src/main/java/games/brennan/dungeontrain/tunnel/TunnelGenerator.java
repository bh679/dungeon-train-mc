package games.brennan.dungeontrain.tunnel;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.event.TunnelChunkEvents;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3dc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Dispatcher that paints stone-brick tunnels around the train's path.
 * Detection + qualification happen here; the actual block placement is
 * delegated to {@link TunnelTemplate} (NBT-backed, editable via
 * {@code /dungeontrain editor enter tunnel_section|tunnel_portal}) or falls
 * back to {@link LegacyTunnelPaint} when no template has been saved.
 *
 * <p><b>Run-based stamping.</b> A tunnel <i>run</i> is a contiguous block
 * of qualified X columns. For each run visible in the chunk's 16-column
 * window (with single-column peeks into the immediate neighbours to set
 * cross-chunk extension flags correctly) we:</p>
 *
 * <ol>
 *   <li>Stamp {@code tunnel_portal} unmirrored at {@code runStart} (the
 *       actual leftmost qualified column — not rounded to a 10-block
 *       boundary) if the run is ≥ 10 columns long.</li>
 *   <li>Stamp {@code tunnel_portal} mirrored on +X at {@code runEnd - 9}
 *       if the run is ≥ 11 columns long. (For 10-column runs the entrance
 *       alone covers the whole run.) For 11-19 column runs the two stamps
 *       overlap — exit wins the overlap since it stamps second — giving
 *       pyramid facades at both ends.</li>
 *   <li>Stamp {@code tunnel_section} at every {@code sx % 10 == 0}
 *       position that fits entirely in the run's middle region
 *       {@code [runStart + 10, runEnd - 10]}. Absolute alignment (instead
 *       of relative to {@code runStart}) keeps section boundaries coherent
 *       across chunks that each see only part of a long run.</li>
 * </ol>
 *
 * <p>Any qualified column not covered by a stamp falls back to
 * {@link LegacyTunnelPaint#paintTunnelColumn}. Approach trenches
 * (non-qualified columns near a tunnel) always stay procedural.</p>
 *
 * <p>Runs that touch the qualified window edges have unknown actual
 * endpoints — for those we skip the out-of-window portal stamp and rely on
 * the chunk that can see that endpoint to handle it. Middle sections in
 * window-edge runs start at least 10 blocks inside the window so they
 * can't overlap an unseen entrance/exit stamp.</p>
 */
public final class TunnelGenerator {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 13-wide tunnel needs ±2 chunks on Z to be covered by the view-distance sweep. */
    private static final int Z_CHUNK_MARGIN = 2;

    /** Max previously-unprocessed chunks to fill per periodic scan — same budget as track fill. */
    private static final int CHUNKS_PER_SCAN_BUDGET = 1;

    /** Minimum qualified-column run length that uses a portal stamp at all. */
    private static final int MIN_PORTAL_RUN = 10;

    /** Minimum run length for stamping BOTH entrance and exit (10-col runs get entrance only). */
    private static final int MIN_DUAL_PORTAL_RUN = 11;

    private TunnelGenerator() {}

    /** A contiguous block of qualified columns detected in a chunk's qualified window. */
    private record Run(int start, int end, boolean extendsLeft, boolean extendsRight) {
        int length() { return end - start + 1; }
    }

    /**
     * Ensure tunnel blocks exist in the given chunk for the given track geometry.
     * Caches the chunk key on completion so subsequent calls exit in O(1).
     *
     * <p><b>Two-phase detection.</b> First a single-block probe at the chunk
     * centre column above the cleared corridor; if the probe sees open sky
     * (or water / leaves / any non-underground material) we mark the chunk
     * processed and bail — the common case in flat / ocean terrain. Only
     * when the probe hits underground material do we scan the chunk's 16
     * columns, validate roof material at run boundaries, and stamp the
     * tunnel. See {@code plans/peaceful-sprouting-sonnet.md}.</p>
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

        long t0 = System.nanoTime();

        // PHASE 1 — Cheap probe at the chunk's centre column, above the
        // carved corridor. One block read. If this is air / water / leaves /
        // anything that isn't natural underground material, the chunk has
        // open sky over the corridor and there's no tunnel work to do.
        int probeX = chunkMinX + 8;
        BlockPos.MutableBlockPos probePos = new BlockPos.MutableBlockPos();
        probePos.set(probeX, tg.ceilingY() + 5, tg.centerZ());
        if (!level.hasChunkAt(probePos)) {
            return;
        }
        boolean probeHit = TunnelPalette.isUndergroundMaterial(level.getBlockState(probePos));
        long tAfterProbe = System.nanoTime();
        if (!probeHit) {
            tunnelFilledChunks.add(chunkKey);
            long dtProbeMs = (tAfterProbe - t0) / 1_000_000;
            if (dtProbeMs > 5) {
                LOGGER.debug("[DungeonTrain] tunnel.probe slow: chunk=({},{}) probe={}ms (miss)",
                    chunkX, chunkZ, dtProbeMs);
            }
            return;
        }

        // PHASE 2 — Probe hit underground material. Scan the chunk's 16 X
        // columns + cross-chunk peeks. Run ends get full roof verification.
        boolean[] qualified = new boolean[16];
        for (int dx = 0; dx < 16; dx++) {
            qualified[dx] = isColumnUnderground(level, chunkMinX + dx, tg);
        }
        boolean prevEdgeQualified = qualified[0]
            && isColumnUnderground(level, chunkMinX - 1, tg);
        boolean nextEdgeQualified = qualified[15]
            && isColumnUnderground(level, chunkMaxX + 1, tg);
        long tAfterScan = System.nanoTime();

        List<Run> rawRuns = detectRuns(qualified, chunkMinX);
        List<Run> runs = new ArrayList<>(rawRuns.size());
        for (Run r : rawRuns) {
            boolean extL = r.extendsLeft && prevEdgeQualified;
            boolean extR = r.extendsRight && nextEdgeQualified;
            if (!extL && !verifyTunnelRoof(level, r.start, tg)) continue;
            if (!extR && !verifyTunnelRoof(level, r.end, tg)) continue;
            runs.add(new Run(r.start, r.end, extL, extR));
        }
        long tAfterDetect = System.nanoTime();

        // Stamp portals + sections.
        boolean[] covered = new boolean[16];
        int stampsPlaced = 0;
        for (Run r : runs) {
            stampRun(level, r, tg, chunkMinX, chunkMaxX, covered);
            stampsPlaced++;
        }
        long tAfterStamp = System.nanoTime();

        // Procedural fallback for non-covered qualified columns.
        int proceduralCols = 0;
        for (int worldX = chunkMinX; worldX <= chunkMaxX; worldX++) {
            if (covered[worldX - chunkMinX]) continue;
            int idx = worldX - chunkMinX;
            if (qualified[idx]) {
                LegacyTunnelPaint.paintTunnelColumn(level, worldX, tg);
                proceduralCols++;
                boolean prev = idx > 0 ? qualified[idx - 1] : prevEdgeQualified;
                boolean next = idx + 1 < 16 ? qualified[idx + 1] : nextEdgeQualified;
                if (!prev || !next) {
                    LegacyTunnelPaint.placePortalPyramid(level, worldX, tg);
                }
            }
        }
        long tAfterPaint = System.nanoTime();

        tunnelFilledChunks.add(chunkKey);

        // Diagnostic — prints sub-phase ms for chunks that took > 50ms total,
        // so we can attribute the spike (probe vs scan vs detect vs stamp vs paint).
        long totalMs = (tAfterPaint - t0) / 1_000_000;
        if (totalMs > 50) {
            LOGGER.debug("[DungeonTrain] tunnel.slow chunk=({},{}) total={}ms probe={}ms scan={}ms detect={}ms stamp={}ms paint={}ms runs={} stamps={} procCols={} qualifiedCount={}",
                chunkX, chunkZ, totalMs,
                (tAfterProbe - t0) / 1_000_000,
                (tAfterScan - tAfterProbe) / 1_000_000,
                (tAfterDetect - tAfterScan) / 1_000_000,
                (tAfterStamp - tAfterDetect) / 1_000_000,
                (tAfterPaint - tAfterStamp) / 1_000_000,
                runs.size(), stampsPlaced, proceduralCols,
                countTrue(qualified));
        }
    }

    private static int countTrue(boolean[] a) {
        int c = 0;
        for (boolean b : a) if (b) c++;
        return c;
    }

    /**
     * Scan the {@code qualified} window and collect every contiguous block
     * of qualified columns. Window-edge runs (first / last index qualified)
     * have {@code extendsLeft} / {@code extendsRight} set so the stamping
     * logic can skip out-of-window portal placements.
     */
    private static List<Run> detectRuns(boolean[] qualified, int baseX) {
        List<Run> runs = new ArrayList<>();
        int i = 0;
        while (i < qualified.length) {
            if (!qualified[i]) { i++; continue; }
            int startIdx = i;
            while (i < qualified.length && qualified[i]) i++;
            int endIdx = i - 1;
            runs.add(new Run(
                baseX + startIdx,
                baseX + endIdx,
                startIdx == 0,
                endIdx == qualified.length - 1
            ));
        }
        return runs;
    }

    /**
     * Stamp the portals + middle sections for a single run, and mark the
     * chunk columns each stamp covers in the {@code covered} array. Stamps
     * whose origin is outside this chunk's [chunkMinX, chunkMaxX] range
     * are NOT written by this chunk — the chunk that contains that origin
     * will write them on its own pass — but we still mark their covered
     * columns so this chunk skips procedural paint on them.
     */
    private static void stampRun(ServerLevel level, Run r, TunnelGeometry tg,
                                 int chunkMinX, int chunkMaxX, boolean[] covered) {
        if (r.length() < MIN_PORTAL_RUN) return;

        // Entrance portal — stamped only when we actually see the run's
        // left edge (otherwise the true runStart is somewhere past the
        // window and another chunk will stamp it).
        if (!r.extendsLeft) {
            stampPortalAt(level, r.start, tg, false, chunkMinX, chunkMaxX, covered);
        }

        // Exit portal — needs runLength ≥ 11 so it doesn't self-overlap
        // at runLength == 10 (in which case entrance alone covers the run).
        if (!r.extendsRight && r.length() >= MIN_DUAL_PORTAL_RUN) {
            stampPortalAt(level, r.end - 9, tg, true, chunkMinX, chunkMaxX, covered);
        }

        // Middle sections packed tightly after the entrance portal —
        // alignment is relative to {@code r.start} (not world-absolute
        // {@code worldX % 10 == 0}) so sections land flush against the
        // portal regardless of where the run starts. For a 30-column run
        // beginning at worldX=53 this gives entrance [53..62], section
        // [63..72], exit [73..82] with zero procedural gap. Absolute
        // alignment would have lost the middle section entirely (firstSx=70,
        // sx+9=79 > middleEnd=72 → no stamp).
        //
        // Runs wider than the 56-column qualified window can't see both
        // ends from a single chunk. Chunks where {@code extendsLeft} is
        // true derive section alignment from the window-edge {@code r.start}
        // instead of the real run start, so sections may misalign across
        // chunk boundaries for very long tunnels — visible but rare.
        int middleStart = r.start + 10;
        int middleEnd;
        if (r.extendsRight) {
            middleEnd = r.end - 10;
        } else if (r.length() >= MIN_DUAL_PORTAL_RUN) {
            middleEnd = r.end - 10;
        } else {
            // runLength is 10 — entrance covers everything, no middle.
            middleEnd = r.start - 1;
        }

        for (int sx = middleStart; sx + 9 <= middleEnd; sx += 10) {
            stampSectionAt(level, sx, tg, chunkMinX, chunkMaxX, covered);
        }
    }

    /** Stamp (or mark-covered) a portal at {@code origin} — actual
     *  placeInWorld call only runs if origin is in this chunk's X range
     *  AND the 2×2 chunk footprint is loaded. Covered[] is marked whether
     *  we stamp or not, so procedural paint stays off those columns. */
    private static void stampPortalAt(ServerLevel level, int origin, TunnelGeometry tg,
                                      boolean mirrorX, int chunkMinX, int chunkMaxX,
                                      boolean[] covered) {
        markCovered(covered, origin, origin + 9, chunkMinX, chunkMaxX);
        if (origin >= chunkMinX && origin <= chunkMaxX
            && sectionChunksLoaded(level, origin, TunnelTemplate.LENGTH, tg)) {
            TunnelTemplate.placePortalAt(
                level,
                new BlockPos(origin, tg.floorY(), tg.wallMinZ()),
                mirrorX
            );
        }
    }

    /** Same contract as {@link #stampPortalAt} but places the section template. */
    private static void stampSectionAt(ServerLevel level, int origin, TunnelGeometry tg,
                                       int chunkMinX, int chunkMaxX, boolean[] covered) {
        markCovered(covered, origin, origin + 9, chunkMinX, chunkMaxX);
        if (origin >= chunkMinX && origin <= chunkMaxX
            && sectionChunksLoaded(level, origin, TunnelTemplate.LENGTH, tg)) {
            TunnelTemplate.placeSectionAt(
                level,
                new BlockPos(origin, tg.floorY(), tg.wallMinZ())
            );
        }
    }

    private static void markCovered(boolean[] covered, int worldMinX, int worldMaxX,
                                    int chunkMinX, int chunkMaxX) {
        int lo = Math.max(worldMinX, chunkMinX);
        int hi = Math.min(worldMaxX, chunkMaxX);
        for (int wx = lo; wx <= hi; wx++) {
            covered[wx - chunkMinX] = true;
        }
    }

    /**
     * Check that every chunk the 10×14×13 section footprint overlaps is
     * currently loaded. A section spans chunks on both X (when it crosses
     * a 16-block boundary) and Z (the 13-wide wall span always straddles
     * at least 2 chunks on Z).
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
     * Single-sample probe: one block read at
     * {@code (worldX, ceilingY + 5, centerZ)}. Returns {@code true} when the
     * sampled block is natural underground material AND the chunk holding it
     * is loaded.
     *
     * <p>This is the lightweight per-column qualification used by both Phase 1
     * (the chunk-centre probe in {@link #ensureTunnelForChunk}) and Phase 2
     * (the 16-column scan inside the chunk plus single-column peeks into
     * neighbours). Run boundaries get extra rigor via
     * {@link #verifyTunnelRoof}, which samples the corridor's full Z width.</p>
     *
     * <p>The y of {@code ceilingY + 5} sits two rows above the tunnel's arched
     * apex (apex at {@code ceilingY + 4}), so a column qualifies only when the
     * full arched silhouette is buried — a 3-block-thick mountain roof
     * disqualifies because the arch would poke out the top.</p>
     */
    public static boolean isColumnUnderground(ServerLevel level, int worldX, TunnelGeometry tg) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        pos.set(worldX, tg.ceilingY() + 5, tg.centerZ());
        if (!level.hasChunkAt(pos)) return false;
        return TunnelPalette.isUndergroundMaterial(level.getBlockState(pos));
    }

    /**
     * Roof verification at a tunnel-end column. Confirms underground material
     * at three Z positions ({@code trackZMin / centerZ / trackZMax}) along the
     * ceiling row at {@code ceilingY + 5}. Catches the case where the
     * single-column probe passes (because the centre column happens to be
     * solid) but the corridor's side columns are open — i.e. a thin rocky
     * outcrop that shouldn't get a portal stamp.
     *
     * <p>Three reads. Called once per non-cross-chunk-extending run boundary,
     * so at most twice per chunk-with-tunnel.</p>
     */
    static boolean verifyTunnelRoof(ServerLevel level, int worldX, TunnelGeometry tg) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int y = tg.ceilingY() + 5;
        int[] zs = { tg.trackZMin(), tg.centerZ(), tg.trackZMax() };
        for (int z : zs) {
            pos.set(worldX, y, z);
            if (!level.hasChunkAt(pos)) return false;
            if (!TunnelPalette.isUndergroundMaterial(level.getBlockState(pos))) return false;
        }
        return true;
    }

    /**
     * Worldgen-time tunnel space placement. For each X column in this chunk,
     * qualify against natural underground material at {@code ceilingY+5/6}
     * (single-X read; no neighbour-chunk dependency) and, if qualified, paint
     * the procedural tunnel column (floor, rails, airspace clearance, walls,
     * lamps, arched roof) via {@link LegacyTunnelPaint#paintTunnelColumnWorldgen}.
     *
     * <p>Run detection, portal/section template stamps, and approach trenches
     * are NOT done here — those need a ±20-column window that exceeds the
     * single-chunk worldgen scope and stay on the runtime drain (
     * {@link #fillRenderDistance}) until item 6.</p>
     *
     * <p>Cross-chunk Z writes (walls at trackZMin-4 and trackZMax+4, plus arch
     * tier overflow) stay inside the immediate Z-neighbour chunk, within
     * NeoForge's standard 3×3 decoration window.</p>
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

        // Fast Z-corridor prefilter — only chunks containing the corridor
        // center can qualify columns (the underground samples land at
        // trackZMin/centerZ/trackZMax). Off-corridor chunks have nothing to
        // qualify and skip cleanly.
        if (chunkMaxZ < tg.trackZMin() || chunkMinZ > tg.trackZMax()) return;

        for (int worldX = chunkMinX; worldX <= chunkMaxX; worldX++) {
            if (!isColumnUndergroundWorldgen(level, worldX, tg)) continue;
            LegacyTunnelPaint.paintTunnelColumnWorldgen(level, worldX, tg);
        }
    }

    /**
     * Worldgen-friendly variant of {@link #isColumnUnderground}. Reads
     * through {@link WorldGenLevel} and skips the {@code level.hasChunkAt}
     * gate (worldgen guarantees the current chunk is loaded; we only sample
     * Z values inside the corridor, all in this chunk's Z range).
     */
    static boolean isColumnUndergroundWorldgen(WorldGenLevel level, int worldX, TunnelGeometry tg) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int[] ys = { tg.ceilingY() + 5, tg.ceilingY() + 6 };
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

    /**
     * Drain up to {@link #CHUNKS_PER_SCAN_BUDGET} chunks of work per call —
     * first the pending queue populated by {@code TunnelChunkEvents}, then a
     * sweep of the view-distance corridor on X × ±{@link #Z_CHUNK_MARGIN} on Z.
     */
    public static void fillRenderDistance(ServerLevel level, ManagedShip ship, TrainTransformProvider provider) {
        TrackGeometry g = provider.getTrackGeometry();
        if (g == null) return;

        long t0 = System.nanoTime();

        Set<Long> filled = provider.getTunnelFilledChunks();
        Set<Long> pending = provider.getPendingTunnelChunks();
        int budget = CHUNKS_PER_SCAN_BUDGET;
        int drainedFromPending = 0;
        int pendingHasChunkChecks = 0;
        int pendingSizeAtStart = pending.size();

        if (!pending.isEmpty()) {
            Iterator<Long> it = pending.iterator();
            while (budget > 0 && it.hasNext()) {
                long key = it.next();
                it.remove();
                int cx = ChunkPos.getX(key);
                int cz = ChunkPos.getZ(key);
                if (filled.contains(key)) continue;
                if (TrackGenerator.isShipyardChunk(cx, cz)) continue;
                pendingHasChunkChecks++;
                if (!level.getChunkSource().hasChunk(cx, cz)) continue;
                // Defer paint while the chunk's autosave is in flight — the
                // upstream race between server-thread mutation and IOWorker
                // NBT iteration causes a sporadic CME otherwise.
                if (TunnelChunkEvents.isSaveBusy(key)) {
                    pending.add(key);
                    continue;
                }
                ensureTunnelForChunk(level, cx, cz, g, filled);
                budget--;
                drainedFromPending++;
            }
        }

        long tAfterPending = System.nanoTime();

        int scanned = 0;
        int scanHasChunkChecks = 0;
        int scanCells = 0;
        if (budget > 0) {
            int viewDistance = level.getServer().getPlayerList().getViewDistance();
            if (viewDistance <= 0) viewDistance = 10;

            Vector3dc shipWorldPos = ship.currentWorldPosition();
            int centerCx = (int) Math.floor(shipWorldPos.x()) >> 4;
            int centerCz = g.trackCenterZ() >> 4;

            for (int cz = centerCz - Z_CHUNK_MARGIN; cz <= centerCz + Z_CHUNK_MARGIN && budget > 0; cz++) {
                for (int cx = centerCx - viewDistance; cx <= centerCx + viewDistance && budget > 0; cx++) {
                    scanCells++;
                    if (TrackGenerator.isShipyardChunk(cx, cz)) continue;
                    long key = ChunkPos.asLong(cx, cz);
                    if (filled.contains(key)) continue;
                    scanHasChunkChecks++;
                    if (!level.getChunkSource().hasChunk(cx, cz)) continue;
                    // Same save-busy guard as the pending-drain path above —
                    // sweep will revisit the chunk on a later tick once
                    // IOWorker has finished serialising its NBT.
                    if (TunnelChunkEvents.isSaveBusy(key)) continue;
                    ensureTunnelForChunk(level, cx, cz, g, filled);
                    budget--;
                    scanned++;
                }
            }
        }

        long tEnd = System.nanoTime();
        long totalMs = (tEnd - t0) / 1_000_000;
        if (totalMs > 50) {
            LOGGER.info("[tunnel.fill.slow] total={}ms pending={}ms scan={}ms pendingSize={} pendingHasChunk={} drained={} scanCells={} scanHasChunk={} scanned={} filled.size={}",
                totalMs,
                (tAfterPending - t0) / 1_000_000,
                (tEnd - tAfterPending) / 1_000_000,
                pendingSizeAtStart, pendingHasChunkChecks, drainedFromPending,
                scanCells, scanHasChunkChecks, scanned, filled.size());
        }

        if (budget < CHUNKS_PER_SCAN_BUDGET && LOGGER.isDebugEnabled()) {
            LOGGER.debug("[DungeonTrain] fillTunnelRenderDistance ship={} drained={} (pending={} scan={}) filled.size={} pending.size={}",
                ship.id(), CHUNKS_PER_SCAN_BUDGET - budget, drainedFromPending, scanned,
                filled.size(), pending.size());
        }
    }
}
