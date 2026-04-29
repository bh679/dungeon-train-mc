package games.brennan.dungeontrain.track;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.PillarTemplateStore;
import games.brennan.dungeontrain.editor.TrackTemplateStore;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.ShipFilterProcessor;
import games.brennan.dungeontrain.ship.Shipyard;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.Vec3i;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.joml.Vector3dc;
import org.slf4j.Logger;

import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
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

    /**
     * Fixed-size Z margin for the view-distance sweep. Needs to cover both the
     * 5-wide track corridor and the 13-wide tunnel wall span. ±2 is enough for
     * any train whose corridor straddles one chunk boundary.
     */
    private static final int Z_CHUNK_MARGIN = 2;

    /**
     * Max new (unprocessed) chunks to fill per periodic scan. Caps the
     * per-tick server-thread cost. See class-level javadoc for budget reasoning.
     */
    private static final int CHUNKS_PER_SCAN_BUDGET = 4;

    /** Stairs adjunct footprint: 3 along X (track direction) × 8 tall × 3 outward. */
    private static final int STAIRS_X = 3;
    private static final int STAIRS_Y = 8;
    private static final int STAIRS_Z = 3;

    /**
     * Minimum spacing between stair-adjuncted pillars, in blocks. Must be a
     * multiple of {@link #BASE_PILLAR_SPACING} so that at least one pillar on
     * flat terrain lands on a multiple of this value (otherwise stairs never
     * appear). 40 blocks ≈ 5 flat-terrain pillar slots.
     */
    private static final int MIN_STAIRS_SPACING = 40;

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
            || state.is(Blocks.VINE)
            // Ice family treated as water — pillars sink through to the
            // seabed below an iced-over ocean / lake instead of resting
            // on the ice surface (which would leave the pillar floating
            // a few blocks above true ground).
            || state.is(BlockTags.ICE)
            // Snow layers, tall grass, flowers, ferns, dead bush, kelp,
            // seagrass, and other replaceable surface adornments occupy
            // a full Y coordinate but render with reduced height — without
            // this guard, a snow layer on top of dirt makes the pillar
            // sit one block above the visible surface. BlockTags.REPLACEABLE
            // is vanilla's "things worldgen can paint over", which is
            // exactly the right superset.
            || state.is(BlockTags.REPLACEABLE);
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
        Shipyard shipyard = Shipyards.of(level);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight() + 1;
        for (int py = bedY - 1; py >= minY; py--) {
            pos.set(x, py, z);
            if (shipyard.isInShip(pos)) {
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
     * Cascading-step-down probe that finds the deepest groundY across a
     * footprint of Z columns at one X. Replaces N independent
     * {@link #probeGroundY} calls (one per column) with a shared-depth
     * search: scan one anchor column down to find ground, then check the
     * remaining columns at the anchor's ground-block level. Columns whose
     * block at that level is non-passable are resolved without further
     * reads (their terrain matches or is shallower than the anchor's).
     * Columns showing passable have terrain BELOW the anchor's level —
     * one of them becomes the next anchor and we resume scanning from one
     * row below the prior ground. Repeat until every column is resolved.
     *
     * <p>For uniform-terrain footprints (the common case in flat / ocean
     * biomes) this collapses {@code N × probeGroundY} from {@code 7 × ~depth}
     * reads to {@code depth + 6} reads. Terrain with cliff edges in the
     * corridor pays the additional descent only for the deepest columns,
     * not all 7.</p>
     *
     * <p>Output equivalence: returns {@code min(probeGroundY) across columns}
     * — exactly what {@link #placePillarSlice} uses as the slice anchor.
     * Ship-intercepted and void columns are excluded from the minimum,
     * matching the existing per-column skip.</p>
     */
    private static int probeDeepestGroundY(
        ServerLevel level, int worldX, int zMin, int zMax, int bedY
    ) {
        Shipyard shipyard = Shipyards.of(level);
        int N = zMax - zMin + 1;
        boolean[] resolved = new boolean[N];
        int unresolved = N;
        int deepest = bedY;                            // bedSentinel = "no useful ground"
        int currentIdx = N / 2;                        // anchor = centre column
        int currentY = bedY - 1;
        int minY = level.getMinBuildHeight() + 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        while (unresolved > 0) {
            if (resolved[currentIdx]) {
                int next = -1;
                for (int i = 0; i < N; i++) {
                    if (!resolved[i]) { next = i; break; }
                }
                if (next < 0) break;
                currentIdx = next;
                currentY = bedY - 1;
            }
            int currentZ = zMin + currentIdx;

            int groundBlockY = -1;
            boolean shipAbort = false;
            for (int y = currentY; y >= minY; y--) {
                pos.set(worldX, y, currentZ);
                if (shipyard.isInShip(pos)) { shipAbort = true; break; }
                if (!isPassable(level.getBlockState(pos))) { groundBlockY = y; break; }
            }
            resolved[currentIdx] = true;
            unresolved--;

            if (shipAbort || groundBlockY < 0) {
                // Ship intercept or void — column doesn't contribute. Pick the
                // next unresolved column from full bedY-1; can't reuse currentY
                // because it was scoped to a different (now-skipped) column.
                currentY = bedY - 1;
                continue;
            }

            int groundY = groundBlockY + 1;
            if (groundY < deepest) deepest = groundY;

            // Side-check unresolved columns at the just-found ground BLOCK level.
            int nextIdx = -1;
            for (int i = 0; i < N; i++) {
                if (resolved[i]) continue;
                pos.set(worldX, groundBlockY, zMin + i);
                if (shipyard.isInShip(pos)) {
                    resolved[i] = true; unresolved--;
                    continue;
                }
                if (!isPassable(level.getBlockState(pos))) {
                    // Column's terrain extends to or above this y → not deeper
                    // than the current anchor. Resolved without contribution.
                    resolved[i] = true; unresolved--;
                } else if (nextIdx < 0) {
                    // First passable side-check becomes the next anchor.
                    nextIdx = i;
                }
            }
            if (nextIdx >= 0) {
                currentIdx = nextIdx;
                currentY = groundBlockY - 1;            // resume strictly below prior ground
            }
        }
        return deepest;
    }

    /**
     * Place bed + rail cells for one column. When {@code tile} is present,
     * the cell at {@code [worldX mod TILE_LENGTH][y][worldZ - trackZMin]} is
     * stamped for each row; {@code null} entries (captured air in the template)
     * are skipped so the user can author viaduct gaps. When {@code tile} is
     * empty, falls back to the hardcoded 5-wide bed + 2-block-gauge rail
     * behavior so worlds with no saved template look identical to the
     * pre-feature mod.
     *
     * <p>Does NOT touch pillar/arch blocks — that's handled by the caller
     * using the precomputed pillar map.</p>
     *
     * @return false if the bed position is ship-owned (caller should skip the
     *         pillar slice for this column too); true otherwise.
     */
    /**
     * Per-tile data the column placer needs: the unpacked template cells,
     * the per-block variants sidecar (may be empty), the world seed, and
     * the tile's index so the deterministic per-block pick is stable
     * across reloads. {@code tile} can be empty (caller falls back to the
     * hardcoded bed + rail palette); {@code sidecar} is null when the tile
     * has no {@code .variants.json} alongside it.
     */
    private record TilePaint(
        Optional<BlockState[][][]> cells,
        TrackVariantBlocks sidecar,
        long worldSeed,
        long tileIndex
    ) {
        BlockState resolveSidecar(BlockState base, int xMod, int y, int zOff) {
            if (sidecar == null || sidecar.isEmpty() || base == null) return base;
            BlockPos local = new BlockPos(xMod, y, zOff);
            games.brennan.dungeontrain.editor.VariantState picked = sidecar.resolve(
                local, worldSeed, (int) tileIndex);
            if (picked == null) return base;
            return games.brennan.dungeontrain.editor.RotationApplier.apply(
                picked.state(), picked.rotation(),
                local, worldSeed, (int) tileIndex,
                sidecar.lockIdAt(local));
        }
    }

    private static boolean placeTrackColumn(
        ServerLevel level,
        int worldX,
        int worldZ,
        TrackGeometry g,
        TilePaint paint
    ) {
        Shipyard shipyard = Shipyards.of(level);
        BlockPos bedPos = new BlockPos(worldX, g.bedY(), worldZ);

        // Skip ship-owned positions — never mutate voxels that belong to our
        // train or any other ship sharing this dimension.
        if (shipyard.isInShip(bedPos)) return false;

        int zOff = worldZ - g.trackZMin();
        int xMod = Math.floorMod(worldX, TrackTemplate.TILE_LENGTH);

        BlockState bedState = paint.cells().isPresent()
            ? paint.cells().get()[xMod][0][zOff]
            : TrackPalette.BED;
        bedState = paint.resolveSidecar(bedState, xMod, 0, zOff);
        if (bedState != null) {
            BlockState existingBed = level.getBlockState(bedPos);
            if (!existingBed.is(bedState.getBlock())) {
                SilentBlockOps.setBlockSilent(level, bedPos, bedState);
            }
        }

        BlockState railState;
        if (paint.cells().isPresent()) {
            railState = paint.cells().get()[xMod][1][zOff];
        } else if (worldZ == g.trackZMin() + 1 || worldZ == g.trackZMax() - 1) {
            railState = TrackPalette.RAIL;
        } else {
            railState = null;
        }
        railState = paint.resolveSidecar(railState, xMod, 1, zOff);
        if (railState != null) {
            BlockPos railPos = new BlockPos(worldX, g.railY(), worldZ);
            if (!shipyard.isInShip(railPos)) {
                BlockState existingRail = level.getBlockState(railPos);
                if (!existingRail.is(railState.getBlock())) {
                    SilentBlockOps.setBlockSilent(level, railPos, railState);
                }
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
     * Place the full track-width pillar slice at {@code worldX}, running from
     * {@code bedY - 1} down to the deepest ground anywhere under the track's
     * {@code [trackZMin..trackZMax]} span. Stamps the three editable
     * {@link PillarSection} templates across the whole Z range per row so the
     * user can design a coherent 1×H×W pillar front face instead of a bunch
     * of identical single-column stacks.
     *
     * <p>Each Z column in the slice is probed independently for its ground,
     * and the minimum ({@code = deepest}) groundY anchors the whole slice.
     * Shallower Z columns simply have their lower rows blocked by terrain —
     * the stamp skips those positions via the non-passable check. The result
     * is a visually-aligned pillar that follows the deepest bedrock and
     * tucks into higher-ground edges naturally.</p>
     *
     * <p>Short-column rule: when the column height {@code H < BOTTOM.height +
     * TOP.height}, the bottom is placed first (up to its full height or
     * {@code H}, whichever is smaller), then the top template fills the
     * remaining rows with its <em>lowest</em> rows truncated, so the
     * decorative cap always lands at the column's top.</p>
     *
     * <p>Missing templates fall back to {@link TrackPalette#PILLAR} per row,
     * matching the pre-template visual exactly. Air entries in a loaded
     * template are left untouched so users can design pillar cut-outs.</p>
     */
    /**
     * One pillar section's resolved paint — the unpacked column cells and
     * the per-block sidecar bundle. Loaded once per pillar slice in
     * {@link #placePillarSlice} since each section runs over up to 4 (TOP) /
     * 1 (MIDDLE) / 3 (BOTTOM) rows.
     */
    private record PillarPaint(
        Optional<BlockState[][]> column,
        TrackVariantBlocks sidecar,
        long worldSeed,
        int pillarIndex
    ) {
        BlockState resolveSidecar(BlockState base, int row, int zIdx) {
            if (sidecar == null || sidecar.isEmpty() || base == null) return base;
            BlockPos local = new BlockPos(0, row, zIdx);
            games.brennan.dungeontrain.editor.VariantState picked = sidecar.resolve(
                local, worldSeed, pillarIndex);
            if (picked == null) return base;
            return games.brennan.dungeontrain.editor.RotationApplier.apply(
                picked.state(), picked.rotation(),
                local, worldSeed, pillarIndex,
                sidecar.lockIdAt(local));
        }
    }

    private static PillarPaint loadPillarPaint(
        ServerLevel level, PillarSection section, CarriageDims dims, long worldSeed, int pillarIndex
    ) {
        TrackKind kind = PillarTemplateStore.pillarKind(section);
        String name = TrackVariantRegistry.pickName(kind, worldSeed, pillarIndex);
        Optional<BlockState[][]> col = PillarTemplateStore.getColumnFor(level, section, dims, name);
        TrackVariantBlocks sidecar = TrackVariantBlocks.loadFor(
            kind, name, new Vec3i(1, section.height(), dims.width()));
        return new PillarPaint(col, sidecar, worldSeed, pillarIndex);
    }

    private static void placePillarSlice(
        ServerLevel level,
        int worldX,
        TrackGeometry g,
        CarriageDims dims
    ) {
        int bedY = g.bedY();
        int topInclusive = bedY - 1;

        // Probe the slice anchor (= deepest groundY across the corridor's
        // Z columns) via the cascading-step-down probe. Equivalent to the
        // previous loop of {@code probeGroundY} per column, but typically
        // ~10× fewer block reads on uniform terrain. Ship-intercepted and
        // void columns are excluded internally — if every column hits one,
        // the helper returns {@code bedY} (sentinel for "no useful ground")
        // and the {@code h <= 0} guard below skips the slice.
        int deepestGroundY = probeDeepestGroundY(level, worldX, g.trackZMin(), g.trackZMax(), bedY);
        int h = topInclusive - deepestGroundY + 1;
        if (h <= 0) return;

        // Pick a registry-weighted variant per section, deterministically by
        // worldSeed + worldX so the same pillar position re-renders identically
        // across reloads. Each section picks independently — themed pillars
        // (matching top/middle/bottom names) are out of scope; authors who
        // want them can bias weights to dominant names.
        long worldSeed = level.getSeed();
        PillarPaint top = loadPillarPaint(level, PillarSection.TOP, dims, worldSeed, worldX);
        PillarPaint mid = loadPillarPaint(level, PillarSection.MIDDLE, dims, worldSeed, worldX);
        PillarPaint bot = loadPillarPaint(level, PillarSection.BOTTOM, dims, worldSeed, worldX);

        int topH = PillarSection.TOP.height();
        int botH = PillarSection.BOTTOM.height();
        int placeBotH = Math.min(botH, h);
        int placeTopH = Math.min(topH, h - placeBotH);
        int placeMidH = h - placeBotH - placeTopH;

        int zMin = g.trackZMin();
        for (int z = g.trackZMin(); z <= g.trackZMax(); z++) {
            int zIdx = z - zMin;
            for (int i = 0; i < placeBotH; i++) {
                stampSliceCell(level, worldX, deepestGroundY + i, z, bot, i, zIdx);
            }
            for (int i = 0; i < placeMidH; i++) {
                stampSliceCell(level, worldX, deepestGroundY + placeBotH + i, z, mid, 0, zIdx);
            }
            for (int i = 0; i < placeTopH; i++) {
                int y = topInclusive - placeTopH + 1 + i;
                int row = (topH - placeTopH) + i;
                stampSliceCell(level, worldX, y, z, top, row, zIdx);
            }
        }
    }

    /**
     * Stamp one cell of a pillar slice. {@code paint} carries the unpacked
     * 2D template (row Y × Z offset) plus the per-block sidecar; null
     * template cells are treated as air. Sidecar resolution applies after
     * the template lookup, so an authored variant can override any cell.
     *
     * <p>Unlike the pre-template per-column early-terminate, this method
     * silently skips ship-owned or non-passable positions without aborting
     * the rest of the slice. That's the right move for a 1×H×W stamp: one
     * shallow Z column hitting terrain shouldn't wipe out the rest of the
     * pillar's face.</p>
     */
    private static void stampSliceCell(
        ServerLevel level,
        int x, int y, int z,
        PillarPaint paint,
        int row,
        int zIdx
    ) {
        BlockPos pos = new BlockPos(x, y, z);
        if (Shipyards.of(level).isInShip(pos)) return;
        BlockState existing = level.getBlockState(pos);
        if (!isPassable(existing)) return;

        Optional<BlockState[][]> column = paint.column();
        BlockState state;
        if (column.isPresent()
            && row >= 0 && row < column.get().length
            && zIdx >= 0 && zIdx < column.get()[row].length) {
            BlockState fromTemplate = column.get()[row][zIdx];
            if (fromTemplate == null) return; // template cell is air — leave passable
            state = fromTemplate;
        } else {
            state = TrackPalette.PILLAR;
        }
        state = paint.resolveSidecar(state, row, zIdx);
        if (state == null) return;
        SilentBlockOps.setBlockSilent(level, pos, state);
    }

    /**
     * Worldgen ground probe — mirrors {@link #probeGroundY} but reads
     * {@link WorldGenLevel} directly and skips the ship check (no ships
     * exist during chunk gen). Walks down from {@code bedY-1} until a
     * non-passable block is hit; returns that block's Y + 1, or
     * {@code minBuildHeight + 1} (void sentinel) if none is found.
     */
    private static int probeGroundYWorldgen(WorldGenLevel level, int x, int z, int bedY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight() + 1;
        for (int py = bedY - 1; py >= minY; py--) {
            pos.set(x, py, z);
            BlockState state = level.getBlockState(pos);
            if (!isPassable(state)) {
                return py + 1;
            }
        }
        return minY;
    }

    /**
     * Worldgen variant of {@link #probeDeepestGroundY}: same cascading
     * algorithm but reads {@link WorldGenLevel} directly and skips the
     * ship-intercept check (no ships exist during chunkgen). See the runtime
     * helper for the full algorithm explanation and equivalence argument.
     */
    private static int probeDeepestGroundYWorldgen(
        WorldGenLevel level, int worldX, int zMin, int zMax, int bedY
    ) {
        int N = zMax - zMin + 1;
        boolean[] resolved = new boolean[N];
        int unresolved = N;
        int deepest = bedY;
        int currentIdx = N / 2;
        int currentY = bedY - 1;
        int minY = level.getMinBuildHeight() + 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        while (unresolved > 0) {
            if (resolved[currentIdx]) {
                int next = -1;
                for (int i = 0; i < N; i++) {
                    if (!resolved[i]) { next = i; break; }
                }
                if (next < 0) break;
                currentIdx = next;
                currentY = bedY - 1;
            }
            int currentZ = zMin + currentIdx;

            int groundBlockY = -1;
            for (int y = currentY; y >= minY; y--) {
                pos.set(worldX, y, currentZ);
                if (!isPassable(level.getBlockState(pos))) { groundBlockY = y; break; }
            }
            resolved[currentIdx] = true;
            unresolved--;

            if (groundBlockY < 0) {
                currentY = bedY - 1;
                continue;
            }

            int groundY = groundBlockY + 1;
            if (groundY < deepest) deepest = groundY;

            int nextIdx = -1;
            for (int i = 0; i < N; i++) {
                if (resolved[i]) continue;
                pos.set(worldX, groundBlockY, zMin + i);
                if (!isPassable(level.getBlockState(pos))) {
                    resolved[i] = true; unresolved--;
                } else if (nextIdx < 0) {
                    nextIdx = i;
                }
            }
            if (nextIdx >= 0) {
                currentIdx = nextIdx;
                currentY = groundBlockY - 1;
            }
        }
        return deepest;
    }

    /**
     * Worldgen-time pillar placement. Each chunk plants the pillars whose
     * center X lies inside its X bounds; the slice (≤ {@code thickness}
     * blocks wide on X, {@code dims.width()} on Z, {@code H} tall) overflows
     * up to ⌊thickness/2⌋ blocks into the immediate neighbour, which is
     * within NeoForge's 3×3 decoration window so {@link WorldGenLevel#setBlock}
     * accepts it. Pillars centered in neighbour chunks place themselves when
     * those chunks generate — no edge gaps.
     *
     * <p>Must be called BEFORE {@link #placeTracksForChunk} during the same
     * Feature.place() invocation so the ground probe sees raw terrain
     * instead of the bed/rail rows.</p>
     */
    public static void placePillarsAtWorldgen(
        WorldGenLevel level,
        ServerLevel serverLevel,
        CarriageDims dims,
        int chunkX,
        int chunkZ,
        TrackGeometry g
    ) {
        if (isShipyardChunk(chunkX, chunkZ)) return;

        int chunkMinX = chunkX << 4;
        int chunkMaxX = chunkMinX + 15;
        int chunkMinZ = chunkZ << 4;
        int chunkMaxZ = chunkMinZ + 15;

        if (chunkMaxZ < g.trackZMin() || chunkMinZ > g.trackZMax()) return;

        int minBuildHeight = level.getMinBuildHeight();
        int maxBuildHeight = level.getMaxBuildHeight();
        if (g.bedY() < minBuildHeight || g.bedY() >= maxBuildHeight) return;

        int probeZ = g.trackCenterZ();
        // Probe Z must be inside our chunk for a single-chunk-only probe.
        // If the corridor center isn't in this chunk's Z range, we can't probe
        // at all — return and let the chunk that contains probeZ do the work.
        if (probeZ < chunkMinZ || probeZ > chunkMaxZ) return;

        long worldSeed = level.getSeed();
        int voidSentinel = minBuildHeight + 1;

        for (int worldX = chunkMinX; worldX <= chunkMaxX; worldX++) {
            int groundY = probeGroundYWorldgen(level, worldX, probeZ, g.bedY());
            if (groundY >= g.bedY()) continue;          // already at/above bed: no pillar
            if (groundY == voidSentinel) continue;       // void column
            int height = g.bedY() - 1 - groundY;
            if (height < 0) continue;
            int spacing = computeSpacing(height);
            if (Math.floorMod(worldX, spacing) != 0) continue;

            int thickness = computeThickness(spacing);
            int minDx = -((thickness - 1) / 2);
            int maxDx = thickness / 2;
            for (int dx = minDx; dx <= maxDx; dx++) {
                placePillarSliceWorldgen(level, serverLevel, worldX + dx, g, dims, worldSeed, worldX);
            }
        }
    }

    private static void placePillarSliceWorldgen(
        WorldGenLevel level,
        ServerLevel serverLevel,
        int worldX,
        TrackGeometry g,
        CarriageDims dims,
        long worldSeed,
        int pillarCenterX
    ) {
        int bedY = g.bedY();
        int topInclusive = bedY - 1;

        // See runtime probeDeepestGroundY for algorithm rationale — same
        // cascading-step-down probe, no ship checks since chunkgen runs
        // before any ship exists.
        int deepestGroundY = probeDeepestGroundYWorldgen(level, worldX, g.trackZMin(), g.trackZMax(), bedY);
        int h = topInclusive - deepestGroundY + 1;
        if (h <= 0) return;

        // Use pillarCenterX as the deterministic seed for paint selection so
        // every column in the same pillar slice picks the same template.
        PillarPaint top = loadPillarPaint(serverLevel, PillarSection.TOP, dims, worldSeed, pillarCenterX);
        PillarPaint mid = loadPillarPaint(serverLevel, PillarSection.MIDDLE, dims, worldSeed, pillarCenterX);
        PillarPaint bot = loadPillarPaint(serverLevel, PillarSection.BOTTOM, dims, worldSeed, pillarCenterX);

        int topH = PillarSection.TOP.height();
        int botH = PillarSection.BOTTOM.height();
        int placeBotH = Math.min(botH, h);
        int placeTopH = Math.min(topH, h - placeBotH);
        int placeMidH = h - placeBotH - placeTopH;

        int zMin = g.trackZMin();
        for (int z = g.trackZMin(); z <= g.trackZMax(); z++) {
            int zIdx = z - zMin;
            for (int i = 0; i < placeBotH; i++) {
                stampSliceCellWorldgen(level, worldX, deepestGroundY + i, z, bot, i, zIdx);
            }
            for (int i = 0; i < placeMidH; i++) {
                stampSliceCellWorldgen(level, worldX, deepestGroundY + placeBotH + i, z, mid, 0, zIdx);
            }
            for (int i = 0; i < placeTopH; i++) {
                int y = topInclusive - placeTopH + 1 + i;
                int row = (topH - placeTopH) + i;
                stampSliceCellWorldgen(level, worldX, y, z, top, row, zIdx);
            }
        }
    }

    private static void stampSliceCellWorldgen(
        WorldGenLevel level,
        int x, int y, int z,
        PillarPaint paint,
        int row,
        int zIdx
    ) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        pos.set(x, y, z);
        BlockState existing = level.getBlockState(pos);
        if (!isPassable(existing)) return;

        Optional<BlockState[][]> column = paint.column();
        BlockState state;
        if (column.isPresent()
            && row >= 0 && row < column.get().length
            && zIdx >= 0 && zIdx < column.get()[row].length) {
            BlockState fromTemplate = column.get()[row][zIdx];
            if (fromTemplate == null) return;
            state = fromTemplate;
        } else {
            state = TrackPalette.PILLAR;
        }
        state = paint.resolveSidecar(state, row, zIdx);
        if (state == null) return;
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
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

        // Fetch per-world dims once; the pillar- and track-template stores
        // validate against this, and the call is cheap (SavedData lookup).
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();

        // Per-tile paint cache (≤ 5 tiles touch any single 16-wide chunk so
        // this map stays tiny). Key = tileIndex (X / TILE_LENGTH); value =
        // the registry-picked name's cells + per-block sidecar bundle.
        long worldSeed = level.getSeed();
        Vec3i tileFootprint = TrackKind.TILE.dims(dims);
        Map<Long, TilePaint> tilePaints = new HashMap<>();

        for (int localX = 0; localX < 16; localX++) {
            int worldX = chunkMinX + localX;
            long tileIndex = Math.floorDiv((long) worldX, (long) TrackTemplate.TILE_LENGTH);
            TilePaint paint = tilePaints.computeIfAbsent(
                tileIndex,
                idx -> {
                    String name = TrackVariantRegistry.pickName(TrackKind.TILE, worldSeed, idx);
                    Optional<BlockState[][][]> cells = TrackTemplateStore.getCellsFor(level, dims, name);
                    TrackVariantBlocks sidecar =
                        TrackVariantBlocks.loadFor(TrackKind.TILE, name, tileFootprint);
                    return new TilePaint(cells, sidecar, worldSeed, idx);
                }
            );

            PillarSpec containingPillar = findPillarContaining(pillars, worldX);

            for (int worldZ = zLo; worldZ <= zHi; worldZ++) {
                placeTrackColumn(level, worldX, worldZ, g, paint);
            }

            if (containingPillar != null) {
                placePillarSlice(level, worldX, g, dims);
                if (worldX == containingPillar.centerX()) {
                    placeStairsBesidePillar(level, containingPillar, pillars, g);
                }
            }
        }

        filledChunks.add(chunkKey);
    }

    /**
     * Place the {@link PillarAdjunct#STAIRS} template alongside {@code pillar}
     * if the selection rule fires for it. Only called once per pillar (at
     * {@code worldX == pillar.centerX()}) so a thick pillar's N columns don't
     * trigger N placements.
     *
     * <p>Selection rule: partition world-X into {@link #MIN_STAIRS_SPACING}-
     * wide windows and place stairs on the <em>first</em> pillar in each
     * window, <em>but only if</em> the previous window's first pillar is at
     * least {@code MIN_STAIRS_SPACING} away — otherwise a pillar just inside
     * the boundary (e.g. centerX=40) could land a step away from one just
     * outside it (e.g. centerX=39). This conservatively skips such close-
     * neighbour placements; the next window's candidate will try again from
     * there. Guarantees minimum 40-block spacing between adjacent stairs,
     * at the cost of an occasional skipped window. The precomputed pillar
     * map already covers ±{@code PILLAR_SCAN_MARGIN}=40 around each chunk,
     * which is exactly enough to resolve both the "first in window" and the
     * "previous window first" lookups. The alternating side is picked off
     * the window index so placement is stable across chunk-load order.</p>
     *
     * <p>Geometry: 3×8×3 template anchored with its top row at
     * {@code g.bedY() + 2} (3 blocks above the track bed, so the top of the
     * staircase sits just above the rail). 3 wide along X centred on
     * {@code centerX}. On the +Z side (non-flipped) the 3 outward Z columns
     * sit at {@code [trackZMax+1 .. trackZMax+3]}; on the -Z side (flipped)
     * at {@code [trackZMin-3 .. trackZMin-1]}. The template is saved in its
     * {@code -Z}-side orientation, so the +Z side gets
     * {@link Mirror#LEFT_RIGHT} applied when stamped. Because that mirror
     * negates local-z, the stamp origin is shifted by {@code +(STAIRS_Z-1)}
     * on the mirrored side so the footprint still lands at the intended
     * {@code [trackZMax+1 .. trackZMax+3]} — same trick {@code TunnelTemplate}
     * uses for its {@code FRONT_BACK}-mirrored portal.</p>
     *
     * <p>If the pillar column is taller than 8, a fresh copy of the template
     * is placed every 8 rows downward until the deepest ground is reached.
     * The bottommost copy is clipped via a {@link BoundingBox} so its lower
     * rows are discarded — the top rows of the template remain aligned with
     * the top of that copy, preserving the "top always on top" invariant.</p>
     */
    private static void placeStairsBesidePillar(
        ServerLevel level,
        PillarSpec pillar,
        NavigableMap<Integer, PillarSpec> pillars,
        TrackGeometry g
    ) {
        int centerX = pillar.centerX();
        int windowIndex = Math.floorDiv(centerX, MIN_STAIRS_SPACING);
        int windowStart = windowIndex * MIN_STAIRS_SPACING;

        // "First pillar in this MIN_STAIRS_SPACING window?" — if there's any
        // pillar with a lower centerX that still falls inside [windowStart,
        // centerX), we're not the first. lowerEntry is O(log n) on the
        // TreeMap. PILLAR_SCAN_MARGIN ≥ MIN_STAIRS_SPACING ensures the
        // relevant prior pillar is always in the map.
        Map.Entry<Integer, PillarSpec> prior = pillars.lowerEntry(centerX);
        if (prior != null && prior.getKey() >= windowStart) return;

        // Distance check: the previous window's first pillar — if any —
        // must be at least MIN_STAIRS_SPACING away. Catches the boundary
        // case where a pillar at windowStart - 1 (first in prev window)
        // and our pillar at windowStart would both qualify as "first in
        // window" but only be 1 apart. Skipping ≥2-window gaps is safe
        // because consecutive windows span ≥80 blocks, exceeding
        // MIN_STAIRS_SPACING, so we only need to check one window back.
        int prevWindowStart = windowStart - MIN_STAIRS_SPACING;
        Map.Entry<Integer, PillarSpec> prevWindowFirst = pillars.ceilingEntry(prevWindowStart);
        if (prevWindowFirst != null
            && prevWindowFirst.getKey() < windowStart
            && centerX - prevWindowFirst.getKey() < MIN_STAIRS_SPACING) {
            return;
        }

        boolean flipped = Math.floorMod(windowIndex, 2) == 1;

        // Pick a registry-weighted variant for this stair instance.
        // tileIndex = centerX so re-walking the same chunk picks the same
        // variant deterministically.
        long worldSeed = level.getSeed();
        String stairsName = TrackVariantRegistry.pickName(
            TrackKind.ADJUNCT_STAIRS, worldSeed, centerX);
        Optional<StructureTemplate> templateOpt =
            PillarTemplateStore.getAdjunctFor(level,
                games.brennan.dungeontrain.track.PillarAdjunct.STAIRS, stairsName);
        if (templateOpt.isEmpty()) return;
        StructureTemplate template = templateOpt.get();
        TrackVariantBlocks stairsSidecar = TrackVariantBlocks.loadFor(
            TrackKind.ADJUNCT_STAIRS, stairsName,
            new Vec3i(STAIRS_X, STAIRS_Y, STAIRS_Z));

        int topInclusive = g.bedY() + 2; // 3 rows above the pillar top so the
                                          // staircase's cap sits above the rail
        int originX = centerX - 1; // centred 3-wide on centerX
        int originZ = flipped ? g.trackZMin() - STAIRS_Z : g.trackZMax() + 1;

        // Probe ground across the 3×3 stair footprint; anchor to the deepest.
        // Skip ship-sentinel and void-sentinel returns (the latter so stairs
        // over pure void — e.g. The End — don't anchor at world floor and
        // hang in mid-air). When every footprint cell is a sentinel,
        // deepestGroundY stays at bedY and the existing check below skips.
        int voidSentinel = level.getMinBuildHeight() + 1;
        int deepestGroundY = g.bedY(); // sentinel "no ground found"
        for (int dx = 0; dx < STAIRS_X; dx++) {
            for (int dz = 0; dz < STAIRS_Z; dz++) {
                int ground = probeGroundY(level, originX + dx, originZ + dz, g.bedY());
                if (ground >= g.bedY()) continue;
                if (ground == voidSentinel) continue;
                if (ground < deepestGroundY) deepestGroundY = ground;
            }
        }
        if (deepestGroundY >= g.bedY()) return;
        if (deepestGroundY > topInclusive) return;

        // When Mirror.LEFT_RIGHT is applied, the template's local Z gets
        // negated around the stamp origin — so the mirrored template
        // extends in -Z from originZ. Shifting the stamp origin by
        // +(STAIRS_Z - 1) makes the final mirrored footprint land at
        // [originZ .. originZ + STAIRS_Z - 1] again. Same pattern
        // TunnelTemplate uses for its FRONT_BACK-mirrored portal.
        int stampOriginZ = !flipped ? originZ + STAIRS_Z - 1 : originZ;

        int currentTop = topInclusive;
        while (currentTop >= deepestGroundY) {
            int remaining = currentTop - deepestGroundY + 1;
            int copyHeight = Math.min(STAIRS_Y, remaining);
            int bottomY = currentTop - copyHeight + 1;

            // placeInWorld uses `origin` for the template's (0,0,0) corner
            // (before mirror/rotation). We always want template Y=STAIRS_Y-1
            // to land at currentTop, so origin Y = currentTop - (STAIRS_Y - 1).
            // The BoundingBox clipping below then discards cells whose world
            // Y is outside [bottomY, currentTop] — exactly the bottom rows on
            // a partial copy.
            BlockPos copyOrigin = new BlockPos(originX, currentTop - (STAIRS_Y - 1), stampOriginZ);

            BoundingBox clip = new BoundingBox(
                originX, bottomY, originZ,
                originX + STAIRS_X - 1, currentTop, originZ + STAIRS_Z - 1
            );

            StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(true)
                .setBoundingBox(clip)
                .addProcessor(ShipFilterProcessor.INSTANCE);
            // Mirror applies to the non-flipped (+Z) side. The saved template
            // is designed to read correctly on the flipped (-Z) side, so we
            // mirror it when stamping on the opposite side to match.
            if (!flipped) settings.setMirror(Mirror.LEFT_RIGHT);

            template.placeInWorld(level, copyOrigin, copyOrigin, settings, level.getRandom(), 3);

            // Sidecar pass — overwrite flagged template-local positions with
            // the deterministic per-block pick. Mirror semantics: on the
            // mirrored (+Z) side template-local Z=k lands at world
            // {@code originZ + STAIRS_Z - 1 - k}; on the flipped (-Z) side
            // it lands at {@code originZ + k}. Cells whose template-local Y
            // would land outside [bottomY, currentTop] are skipped (the
            // BoundingBox above already kept those out of placeInWorld).
            if (!stairsSidecar.isEmpty()) {
                Shipyard shipyard = Shipyards.of(level);
                int templateBaseY = currentTop - (STAIRS_Y - 1);
                for (var entry : stairsSidecar.entries()) {
                    int lx = entry.localPos().getX();
                    int ly = entry.localPos().getY();
                    int lz = entry.localPos().getZ();
                    int wy = templateBaseY + ly;
                    if (wy < bottomY || wy > currentTop) continue;
                    int wx = originX + lx;
                    int wz = flipped ? (originZ + lz) : (originZ + STAIRS_Z - 1 - lz);
                    BlockPos wpos = new BlockPos(wx, wy, wz);
                    if (shipyard.isInShip(wpos)) continue;
                    games.brennan.dungeontrain.editor.VariantState picked =
                        stairsSidecar.resolve(entry.localPos(), worldSeed, centerX);
                    if (picked == null) continue;
                    BlockState rotated = games.brennan.dungeontrain.editor.RotationApplier.apply(
                        picked.state(), picked.rotation(),
                        entry.localPos(), worldSeed, centerX,
                        stairsSidecar.lockIdAt(entry.localPos()));
                    SilentBlockOps.setBlockSilent(level, wpos, rotated, picked.blockEntityNbt());
                }
            }

            currentTop -= STAIRS_Y;
        }
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
     * Worldgen-safe placement of bed + rails for one chunk. Uses the same
     * NBT-template + sidecar pipeline as the legacy live path: per X tile
     * picks a registry-weighted variant by {@code worldSeed + tileIndex}
     * via {@link TrackVariantRegistry#pickName}, unpacks its NBT cells via
     * {@link TrackTemplateStore#getCellsFor}, and resolves per-block
     * {@link TrackVariantBlocks} sidecars deterministically. When a tile
     * has no template, falls back to {@link TrackPalette#BED} +
     * {@link TrackPalette#RAIL}.
     *
     * <p>Called from the {@code TrackBedFeature} during chunk generation
     * — writes through {@link WorldGenLevel#setBlock} so neighbour updates
     * stay inside the chunk-gen sandbox.</p>
     *
     * <p>Contract: caller has already filtered for the active dimension and
     * guarded against shipyard chunks. This method does the corridor
     * intersection check itself (so it's safe to invoke on any chunk in the
     * train's dimension) and clamps to the level's build height — features
     * placed outside the build range are silently skipped.</p>
     *
     * <p>Pillars are deliberately NOT placed here; they read terrain
     * heightmap from up to ±{@link #PILLAR_SCAN_MARGIN} blocks on X, which
     * is unreliable during chunk gen because neighbour chunks may not have
     * generated yet. The legacy post-load drain
     * ({@link #ensureTracksForChunk}) handles them once a train ship is
     * loaded.</p>
     *
     * @param serverLevel the underlying {@code ServerLevel} for the
     *     dimension being generated (from {@link WorldGenLevel#getLevel}).
     *     Required because {@link TrackTemplateStore#getCellsFor} resolves
     *     templates relative to the world's per-install datapack
     *     overrides.
     * @param dims per-world {@link CarriageDims}; templates are keyed by
     *     {@code dims.width()} (the cells array shape varies with width).
     */
    public static void placeTracksForChunk(
        WorldGenLevel level,
        ServerLevel serverLevel,
        CarriageDims dims,
        int chunkX,
        int chunkZ,
        TrackGeometry g
    ) {
        if (isShipyardChunk(chunkX, chunkZ)) return;

        int chunkMinX = chunkX << 4;
        int chunkMaxX = chunkMinX + 15;
        int chunkMinZ = chunkZ << 4;
        int chunkMaxZ = chunkMinZ + 15;

        if (chunkMaxZ < g.trackZMin() || chunkMinZ > g.trackZMax()) return;

        int minBuildHeight = level.getMinBuildHeight();
        int maxBuildHeight = level.getMaxBuildHeight();
        if (g.bedY() < minBuildHeight || g.bedY() >= maxBuildHeight) return;
        boolean canPlaceRail = g.railY() >= minBuildHeight && g.railY() < maxBuildHeight;

        int zLo = Math.max(g.trackZMin(), chunkMinZ);
        int zHi = Math.min(g.trackZMax(), chunkMaxZ);
        int railZA = g.trackZMin() + 1;
        int railZB = g.trackZMax() - 1;

        // Per-tile paint cache. At most ⌈16 / TILE_LENGTH⌉ + 1 tiles touch any
        // single 16-wide chunk, so this map stays tiny. Keyed by tileIndex.
        long worldSeed = level.getSeed();
        Vec3i tileFootprint = TrackKind.TILE.dims(dims);
        Map<Long, TilePaint> tilePaints = new HashMap<>();

        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = chunkMinX; x <= chunkMaxX; x++) {
            long tileIndex = Math.floorDiv((long) x, (long) TrackTemplate.TILE_LENGTH);
            int xMod = Math.floorMod(x, TrackTemplate.TILE_LENGTH);
            TilePaint paint = tilePaints.computeIfAbsent(
                tileIndex,
                idx -> {
                    String name = TrackVariantRegistry.pickName(TrackKind.TILE, worldSeed, idx);
                    Optional<BlockState[][][]> cells =
                        TrackTemplateStore.getCellsFor(serverLevel, dims, name);
                    TrackVariantBlocks sidecar =
                        TrackVariantBlocks.loadFor(TrackKind.TILE, name, tileFootprint);
                    return new TilePaint(cells, sidecar, worldSeed, idx);
                }
            );

            for (int z = zLo; z <= zHi; z++) {
                int zOff = z - g.trackZMin();

                // Bed row (template y=0). null cells = author-authored air —
                // write air explicitly so terrain doesn't show through the gap.
                BlockState bedState = paint.cells().isPresent()
                    ? paint.cells().get()[xMod][0][zOff]
                    : TrackPalette.BED;
                bedState = paint.resolveSidecar(bedState, xMod, 0, zOff);
                pos.set(x, g.bedY(), z);
                level.setBlock(pos, bedState != null ? bedState : air, Block.UPDATE_CLIENTS);

                // Rail row (template y=1). Same null=air rule. In the fallback
                // (no template) only the two outer Z columns get a rail block;
                // the rest of the corridor's rail row gets cleared to air.
                BlockState railState;
                if (paint.cells().isPresent()) {
                    railState = paint.cells().get()[xMod][1][zOff];
                } else if (z == railZA || z == railZB) {
                    railState = TrackPalette.RAIL;
                } else {
                    railState = null;
                }
                railState = paint.resolveSidecar(railState, xMod, 1, zOff);
                if (canPlaceRail) {
                    pos.set(x, g.railY(), z);
                    level.setBlock(pos, railState != null ? railState : air, Block.UPDATE_CLIENTS);
                }
            }
        }

        // Clear the carriage envelope above the rails so terrain never wedges
        // the train at runtime. Bounds: [chunkMinX..chunkMaxX] × [zLo..zHi]
        // × [trainY..trainY+height]. Height covers the carriage's own dims.height()
        // rows (trainY..trainY+height-1) plus one block of headroom above the
        // roof (trainY+height) — the carriage sits one block above the rails,
        // so a "train+1" envelope is the smallest stable clearance.
        int trainY = g.bedY() + 2;
        int clearMinY = Math.max(minBuildHeight, trainY);
        int clearMaxY = Math.min(maxBuildHeight - 1, trainY + dims.height());
        if (clearMaxY >= clearMinY) {
            for (int x = chunkMinX; x <= chunkMaxX; x++) {
                for (int z = zLo; z <= zHi; z++) {
                    for (int y = clearMinY; y <= clearMaxY; y++) {
                        pos.set(x, y, z);
                        if (level.getBlockState(pos).isAir()) continue;
                        level.setBlock(pos, air, Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    /**
     * Place pillars + stair-adjuncts for one chunk using the existing
     * terrain-aware logic. Caller is responsible for ensuring all 8
     * neighbour chunks are at {@code ChunkStatus.FULL} so heightmap reads
     * within ±{@link #PILLAR_SCAN_MARGIN} are reliable, and for stamping
     * the chunk in {@code PillarPaintedChunks} after this returns to keep
     * the deferred pass from re-running.
     *
     * <p>Idempotent: re-running on an already-pillared chunk is a no-op
     * (pillar slice / stairs only write into passable blocks).</p>
     */
    public static void placePillarsForChunk(
        ServerLevel level,
        int chunkX,
        int chunkZ,
        TrackGeometry g,
        CarriageDims dims
    ) {
        if (isShipyardChunk(chunkX, chunkZ)) return;

        int chunkMinX = chunkX << 4;
        int chunkMaxX = chunkMinX + 15;
        int chunkMinZ = chunkZ << 4;
        int chunkMaxZ = chunkMinZ + 15;

        if (chunkMaxZ < g.trackZMin() || chunkMinZ > g.trackZMax()) return;

        NavigableMap<Integer, PillarSpec> pillars = computePillarPositions(
            level,
            chunkMinX - PILLAR_SCAN_MARGIN,
            chunkMaxX + PILLAR_SCAN_MARGIN,
            g
        );

        for (int localX = 0; localX < 16; localX++) {
            int worldX = chunkMinX + localX;
            PillarSpec containingPillar = findPillarContaining(pillars, worldX);
            if (containingPillar == null) continue;

            placePillarSlice(level, worldX, g, dims);
            if (worldX == containingPillar.centerX()) {
                placeStairsBesidePillar(level, containingPillar, pillars, g);
            }
        }
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
    public static void bootstrapPendingChunks(ServerLevel level, ManagedShip ship, TrainTransformProvider provider) {
        TrackGeometry g = provider.getTrackGeometry();
        if (g == null) return;

        int viewDistance = level.getServer().getPlayerList().getViewDistance();
        if (viewDistance <= 0) viewDistance = 10;

        Vector3dc shipWorldPos = ship.currentWorldPosition();
        int centerCx = (int) Math.floor(shipWorldPos.x()) >> 4;
        int centerCz = g.trackCenterZ() >> 4;

        Deque<Long> pending = provider.getPendingChunks();
        Set<Long> filled = provider.getFilledChunks();
        int queued = 0;
        int skippedFeaturePainted = 0;
        // Probe Y/Z computed once — bedY and trackCenterZ are stable for the
        // train's lifetime, so the cost is one BlockPos + one getBlockState
        // per loaded chunk.
        int probeY = g.bedY();
        int probeZ = g.trackCenterZ();
        for (int cz = centerCz - Z_CHUNK_MARGIN; cz <= centerCz + Z_CHUNK_MARGIN; cz++) {
            for (int cx = centerCx - viewDistance; cx <= centerCx + viewDistance; cx++) {
                if (isShipyardChunk(cx, cz)) continue;
                if (!level.getChunkSource().hasChunk(cx, cz)) continue;
                long key = ChunkPos.asLong(cx, cz);
                if (filled.contains(key)) continue;

                // Skip chunks the worldgen TrackBedFeature already painted
                // (bed + rails are part of the chunk save). Without this,
                // every spawn re-runs the heavy pillar+stairs precompute on
                // 100+ chunks for chunks that already have bed in place,
                // wedging "Joining World" behind a ~13 s drain.
                //
                // Probe is bounded to the corridor: trackCenterZ lives in
                // exactly one chunk on Z (cz=0 with default origin), so
                // off-corridor chunks fall through the chunkMinZ/chunkMaxZ
                // gate below and we only test the relevant one. We mark
                // skipped chunks in `filled` so the periodic view-distance
                // sweep doesn't re-discover them either.
                int chunkMinZ = cz << 4;
                int chunkMaxZ = chunkMinZ + 15;
                if (probeZ >= chunkMinZ && probeZ <= chunkMaxZ) {
                    BlockPos probePos = new BlockPos((cx << 4) + 8, probeY, probeZ);
                    if (level.getBlockState(probePos).is(TrackPalette.BED.getBlock())) {
                        filled.add(key);
                        skippedFeaturePainted++;
                        continue;
                    }
                }

                pending.offer(key);
                queued++;
            }
        }
        LOGGER.info("[DungeonTrain] Track bootstrap for ship {}: enqueued {} already-loaded chunks ({} skipped as feature-painted, centerCx={}, viewDistance={})",
            ship.id(), queued, skippedFeaturePainted, centerCx, viewDistance);
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
    public static void fillRenderDistance(ServerLevel level, ManagedShip ship, TrainTransformProvider provider) {
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

            Vector3dc shipWorldPos = ship.currentWorldPosition();
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
                ship.id(), CHUNKS_PER_SCAN_BUDGET - budget, drainedFromPending, scanned,
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
