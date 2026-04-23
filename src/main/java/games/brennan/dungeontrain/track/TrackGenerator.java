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
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Stateless helpers that fill a chunk — or a batch of chunks in the render
 * corridor — with a 5-wide stone-brick track bed, rails, and height-scaled
 * pillars under a running Dungeon Train.
 *
 * <p>All methods run on the server thread. A per-train
 * {@code Set<Long>} (chunk-pos longs) cache lives on
 * {@link TrainTransformProvider#getFilledChunks()} — once a chunk has been
 * processed, it's never iterated again, so periodic scans cost only the set
 * lookups.</p>
 *
 * <p>Pillar layout adapts to terrain depth:</p>
 * <ul>
 *   <li><b>height &lt; 5</b>: spacing {@link #BASE_PILLAR_SPACING}, thickness 1
 *   — matches pre-0.21 behaviour for flat ground.</li>
 *   <li><b>height ≥ 5</b>: spacing = {@code height + BASE_PILLAR_SPACING},
 *   thickness = {@code spacing / 5} (min 1). Over deep terrain, pillars
 *   are further apart and thicker on X.</li>
 * </ul>
 *
 * <p>Arches between pillars are intentionally deferred — they'll come back
 * in a later iteration once spacing and thickness are visually dialled in.</p>
 *
 * <p>To keep any single tick from blowing the server tick budget on a
 * large spawn (e.g. 20 carriages × 21 chunks of render distance × ~80
 * columns = 30k+ {@code setBlock} calls), fills are batched — at most
 * {@link #CHUNKS_PER_SCAN_BUDGET} previously-unprocessed chunks per
 * periodic call.</p>
 */
public final class TrackGenerator {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Base spacing on X for short pillars — matches legacy behaviour. */
    private static final int BASE_PILLAR_SPACING = 8;

    /** Heights at or above this threshold switch on height-scaled spacing + arches. */
    private static final int TALL_PILLAR_HEIGHT_THRESHOLD = 5;

    /**
     * Upper bound on computed pillar spacing. Must not exceed {@link #PILLAR_SCAN_MARGIN}
     * on either side, otherwise the chunk scan would miss pillars whose footprint
     * spans into the current chunk from outside the scan range.
     */
    private static final int MAX_PILLAR_SPACING = 40;

    /**
     * X-axis margin (in blocks) around each chunk when precomputing pillar
     * positions. Must be ≥ {@link #MAX_PILLAR_SPACING} so any arch that
     * touches this chunk has both its anchoring pillars inside the scan.
     */
    private static final int PILLAR_SCAN_MARGIN = 40;

    /**
     * Normal-world gameplay is bounded far below this. Anything beyond is the
     * VS2 shipyard — skip to avoid polluting ship voxel storage.
     */
    private static final long SHIPYARD_COORDINATE_CUTOFF = 10_000_000L;

    /** Fixed-size Z margin when computing chunk corridor — 5-wide track always fits in ±1 chunk. */
    private static final int Z_CHUNK_MARGIN = 1;

    /**
     * Max new (unprocessed) chunks to fill per periodic scan. Caps the
     * per-tick server-thread cost. See class-level javadoc for budget reasoning.
     */
    private static final int CHUNKS_PER_SCAN_BUDGET = 4;

    private TrackGenerator() {}

    /**
     * One pillar's geometry — computed once during the per-chunk precompute
     * and reused by every column in this chunk that lands inside or adjacent
     * to the pillar.
     *
     * @param centerX    world X of the pillar's centerline.
     * @param thickness  number of consecutive X columns this pillar occupies.
     *                   Footprint extends from {@code centerX - (thickness-1)/2}
     *                   through {@code centerX + thickness/2}. Odd thicknesses
     *                   are symmetric; even thicknesses bias one block to the
     *                   right of centerX.
     * @param height     Pillar height at the center-probe column
     *                   ({@code bedY - 1 - centerGroundY}). Used only to gate
     *                   the height-based spacing/thickness rules — <em>not</em>
     *                   to cap how far individual columns descend. Each column
     *                   in the footprint descends to its <em>own</em> ground
     *                   via the per-column terrain check in
     *                   {@link #placePillarColumn}, so corners follow sloped
     *                   ground instead of floating at the center's level.
     */
    private record PillarSpec(int centerX, int thickness, int height) {
        int minX() {
            return centerX - (thickness - 1) / 2;
        }

        int maxX() {
            return minX() + thickness - 1;
        }

        boolean containsX(int worldX) {
            return worldX >= minX() && worldX <= maxX();
        }
    }

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
     * Pure helper: {@code height < 5} → 8 (base), else {@code height + 8}
     * clamped to {@link #MAX_PILLAR_SPACING}. Exposed package-private for unit
     * tests.
     */
    static int computeSpacing(int height) {
        if (height < TALL_PILLAR_HEIGHT_THRESHOLD) return BASE_PILLAR_SPACING;
        int spacing = height + BASE_PILLAR_SPACING;
        return Math.min(spacing, MAX_PILLAR_SPACING);
    }

    /**
     * Pure helper: one pillar-block of thickness per 6 blocks of spacing,
     * minimum 1. 8→1, 13→2, 18→3, 24→4, 30→5. Exposed package-private for
     * unit tests.
     */
    static int computeThickness(int spacing) {
        return Math.max(1, spacing / 6);
    }

    /**
     * Shared passability predicate used by both ground-depth probing and
     * pillar descent. Pillars and probes pass through air, fluids, leaves,
     * and vines — they stop on any other block. Heightmap types can't be
     * used for this because {@code MOTION_BLOCKING_NO_LEAVES} counts water
     * as solid (pillars would float over oceans) and {@code OCEAN_FLOOR}
     * counts leaves as solid (pillars would stop on tree canopies).
     */
    private static boolean isPassable(BlockState state) {
        return state.isAir()
            || !state.getFluidState().isEmpty()
            || state.is(BlockTags.LEAVES)
            || state.is(Blocks.VINE);
    }

    /**
     * Walk down from {@code bedY - 1} until a non-passable block is hit.
     * Returns that block's Y + 1 — i.e., the Y where the pillar's lowest
     * block would sit. If the bed is already sitting on solid terrain,
     * returns {@code bedY} (zero-height pillar). If no ground is found down
     * to {@code minBuildHeight}, returns {@code minBuildHeight + 1}
     * (pillar reaches world bottom).
     *
     * <p>Returns {@code bedY} (sentinel = "no pillar needed / no probe") if
     * any scanned position is inside a VS ship voxel, since the pillar can't
     * stop on a ship.</p>
     */
    private static int probeGroundY(ServerLevel level, int x, int z, int bedY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight() + 1;
        for (int py = bedY - 1; py >= minY; py--) {
            pos.set(x, py, z);
            if (VSGameUtilsKt.getShipObjectManagingPos(level, pos) != null) {
                // Something ship-owned intercepts — treat as "no free pillar column"
                // and return a sentinel that makes this a zero-height pillar (no arch).
                return bedY;
            }
            BlockState state = level.getBlockState(pos);
            if (!isPassable(state)) {
                return py + 1;
            }
        }
        return minY;
    }

    /**
     * Place bed + rails for one column, mirroring legacy behaviour. Does NOT
     * touch pillar/arch blocks — that's handled by the caller using the
     * precomputed pillar map.
     *
     * @return true if the bed was placed (or was already stone brick from a
     *         previous pass). false if ship-blocked — caller should skip
     *         pillar/arch work for this column too.
     */
    private static boolean placeBedAndRail(ServerLevel level, int worldX, int worldZ, TrackGeometry g) {
        BlockPos bedPos = new BlockPos(worldX, g.bedY(), worldZ);

        // Skip ship-owned positions — never mutate voxels that belong to our
        // train or any other VS ship sharing this dimension.
        if (VSGameUtilsKt.getShipObjectManagingPos(level, bedPos) != null) return false;

        // Second-line idempotence — if the bed is already stone brick,
        // something already placed it. Don't overwrite, but allow downstream
        // pillar/arch checks to still run (they have their own idempotence).
        BlockState existingBed = level.getBlockState(bedPos);
        if (!existingBed.is(TrackPalette.BED.getBlock())) {
            level.setBlock(bedPos, TrackPalette.BED, Block.UPDATE_CLIENTS);
        }

        // Rails on top, at the two inner-edge Z rows (2-block gauge).
        if (worldZ == g.trackZMin() + 1 || worldZ == g.trackZMax() - 1) {
            BlockPos railPos = new BlockPos(worldX, g.railY(), worldZ);
            if (VSGameUtilsKt.getShipObjectManagingPos(level, railPos) == null) {
                level.setBlock(railPos, TrackPalette.RAIL, Block.UPDATE_CLIENTS);
            }
        }
        return true;
    }

    /**
     * Precompute pillar positions for X range {@code [scanMinX, scanMaxX]}.
     * Walks X step-by-step, probes ground depth at the corridor center, and
     * records a {@link PillarSpec} at every X satisfying the height-scaled
     * "is a pillar here?" rule.
     *
     * <p>The resulting map is keyed by {@code centerX} and ordered, so
     * {@link NavigableMap#floorEntry} / {@link NavigableMap#ceilingEntry}
     * give O(log n) lookup of enclosing pillar anchors for any column.</p>
     *
     * <p>Pillar placement rule (per X):</p>
     * <ul>
     *   <li>If height &lt; 5: placed iff {@code X % 8 == 0} (legacy grid).</li>
     *   <li>Otherwise: placed iff {@code X % (height + 8) == 0}.</li>
     * </ul>
     *
     * <p>We probe <em>every</em> X (not just even X) because
     * {@link #computeSpacing} can return odd values (e.g. height 5 → spacing
     * 13), so odd-X pillars are legal placements. The cost is ~80 probes per
     * chunk fill — dwarfed by the setBlock cost of the actual fill.</p>
     *
     * <p>Because different X's can have different ground depths (and thus
     * different local spacings), the map can contain irregular gaps — which
     * is the intended visual: pillars cluster closely over shallow ground
     * and space out over deep ravines.</p>
     */
    private static NavigableMap<Integer, PillarSpec> computePillarPositions(
        ServerLevel level,
        int scanMinX,
        int scanMaxX,
        TrackGeometry g
    ) {
        NavigableMap<Integer, PillarSpec> pillars = new TreeMap<>();
        int probeZ = g.trackCenterZ();

        for (int x = scanMinX; x <= scanMaxX; x++) {
            // Skip X's whose probe column lives in an unloaded chunk — probing
            // there would force-load or return garbage. The neighbouring chunk
            // will re-compute pillar positions through this X when it loads.
            if (!level.getChunkSource().hasChunk(x >> 4, probeZ >> 4)) continue;

            int groundY = probeGroundY(level, x, probeZ, g.bedY());
            int height = g.bedY() - 1 - groundY;
            if (height < 0) height = 0;

            int spacing = computeSpacing(height);
            if (Math.floorMod(x, spacing) != 0) continue;

            int thickness = computeThickness(spacing);
            pillars.put(x, new PillarSpec(x, thickness, height));
        }
        return pillars;
    }

    /**
     * Place a solid pillar column at (worldX, worldZ) from {@code bedY - 1}
     * down until non-passable terrain stops the descent. Every column in a
     * pillar's footprint descends independently to <em>its own</em> ground,
     * so a thick pillar on sloped terrain follows the slope instead of
     * floating at the center column's level.
     *
     * <p>Skips ship-owned positions and stops on any non-passable block
     * (same passability rules as {@link #probeGroundY}). Uses
     * {@link TrackPalette#PILLAR}.</p>
     */
    private static void placePillarColumn(
        ServerLevel level,
        int worldX,
        int worldZ,
        int pillarTopInclusive
    ) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight() + 1;
        for (int py = pillarTopInclusive; py >= minY; py--) {
            pos.set(worldX, py, worldZ);
            if (VSGameUtilsKt.getShipObjectManagingPos(level, pos) != null) return;
            BlockState existing = level.getBlockState(pos);
            if (!isPassable(existing)) return; // hit terrain — this column is done
            level.setBlock(pos, TrackPalette.PILLAR, Block.UPDATE_CLIENTS);
        }
    }

    /**
     * Ensure tracks exist in the given chunk for {@code g}. Hit the provider's
     * cache first — chunks already processed exit in O(1). On miss, precompute
     * pillar positions in the chunk ± {@link #PILLAR_SCAN_MARGIN} and walk
     * the chunk's columns, placing bed/rail/pillar blocks as appropriate.
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
        int chunkMaxX = chunkMinX + 15;
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

        // Precompute pillar map over [chunkMinX - margin, chunkMaxX + margin]
        // so that thick pillars anchored just outside this chunk still fill
        // their footprint into this chunk.
        NavigableMap<Integer, PillarSpec> pillars = computePillarPositions(
            level,
            chunkMinX - PILLAR_SCAN_MARGIN,
            chunkMaxX + PILLAR_SCAN_MARGIN,
            g
        );

        for (int localX = 0; localX < 16; localX++) {
            int worldX = chunkMinX + localX;
            PillarSpec containingPillar = findPillarContaining(pillars, worldX);

            for (int worldZ = zLo; worldZ <= zHi; worldZ++) {
                if (!placeBedAndRail(level, worldX, worldZ, g)) continue;

                if (containingPillar != null) {
                    placePillarColumn(level, worldX, worldZ, g.bedY() - 1);
                }
            }
        }

        filledChunks.add(chunkKey);
    }

    /**
     * Return the {@link PillarSpec} whose X-footprint contains {@code worldX},
     * or {@code null} if {@code worldX} sits in the gap between pillars.
     *
     * <p>Checks both the floor and ceiling entries — a thick pillar's center
     * might be either left or right of {@code worldX} while its footprint
     * still covers it.</p>
     */
    private static PillarSpec findPillarContaining(
        NavigableMap<Integer, PillarSpec> pillars,
        int worldX
    ) {
        Map.Entry<Integer, PillarSpec> floor = pillars.floorEntry(worldX);
        if (floor != null && floor.getValue().containsX(worldX)) return floor.getValue();
        Map.Entry<Integer, PillarSpec> ceil = pillars.ceilingEntry(worldX);
        if (ceil != null && ceil.getValue().containsX(worldX)) return ceil.getValue();
        return null;
    }

    /**
     * One-time bootstrap called from {@code TrainAssembler.spawnTrain} after
     * {@link TrackGeometry} is attached. Walks every currently-loaded chunk
     * in the train's Z corridor within view-distance of the spawn point and
     * adds it to the train's pending queue.
     *
     * <p>Necessary because chunks loaded before this tick already fired
     * {@code ChunkEvent.Load} while no train existed, so {@code TrackChunkEvents}
     * skipped them. Without bootstrap they'd be permanently stuck as gaps in
     * the bed — the periodic scan only runs when pending is empty, and
     * pending is refilled continuously by new chunk loads.</p>
     */
    public static void bootstrapPendingChunks(ServerLevel level, ServerShip ship, TrainTransformProvider provider) {
        TrackGeometry g = provider.getTrackGeometry();
        if (g == null) return;

        int viewDistance = level.getServer().getPlayerList().getViewDistance();
        if (viewDistance <= 0) viewDistance = 10;

        Vector3dc shipWorldPos = ship.getTransform().getPosition();
        int centerCx = (int) Math.floor(shipWorldPos.x()) >> 4;
        int centerCz = g.trackCenterZ() >> 4;

        Deque<Long> pending = provider.getPendingChunks();
        Set<Long> filled = provider.getFilledChunks();
        int queued = 0;
        for (int cz = centerCz - Z_CHUNK_MARGIN; cz <= centerCz + Z_CHUNK_MARGIN; cz++) {
            for (int cx = centerCx - viewDistance; cx <= centerCx + viewDistance; cx++) {
                if (isShipyardChunk(cx, cz)) continue;
                if (!level.getChunkSource().hasChunk(cx, cz)) continue;
                long key = ChunkPos.asLong(cx, cz);
                if (filled.contains(key)) continue;
                pending.offer(key);
                queued++;
            }
        }
        LOGGER.info("[DungeonTrain] Track bootstrap for ship {}: enqueued {} already-loaded chunks (centerCx={}, viewDistance={})",
            ship.getId(), queued, centerCx, viewDistance);
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

    /**
     * Suppress unused-import warning — {@link Heightmap} is referenced from
     * javadoc only now, but we keep the import so future contributors see
     * the historical "why not Heightmap" context near the passability logic.
     */
    @SuppressWarnings("unused")
    private static final Class<?> HEIGHTMAP_DOC_ANCHOR = Heightmap.class;
}
