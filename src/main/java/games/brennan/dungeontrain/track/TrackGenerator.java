package games.brennan.dungeontrain.track;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.PillarTemplateStore;
import games.brennan.dungeontrain.editor.TrackTemplateStore;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.tunnel.VSShipFilterProcessor;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.Deque;
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
    private static boolean placeTrackColumn(
        ServerLevel level,
        int worldX,
        int worldZ,
        TrackGeometry g,
        Optional<BlockState[][][]> tile
    ) {
        BlockPos bedPos = new BlockPos(worldX, g.bedY(), worldZ);

        // Skip ship-owned positions — never mutate voxels that belong to our
        // train or any other VS ship sharing this dimension.
        if (VSGameUtilsKt.getShipObjectManagingPos(level, bedPos) != null) return false;

        int zOff = worldZ - g.trackZMin();
        int xMod = Math.floorMod(worldX, TrackTemplate.TILE_LENGTH);

        BlockState bedState = tile.isPresent()
            ? tile.get()[xMod][0][zOff]
            : TrackPalette.BED;
        if (bedState != null) {
            BlockState existingBed = level.getBlockState(bedPos);
            if (!existingBed.is(bedState.getBlock())) {
                SilentBlockOps.setBlockSilent(level, bedPos, bedState);
            }
        }

        BlockState railState;
        if (tile.isPresent()) {
            railState = tile.get()[xMod][1][zOff];
        } else if (worldZ == g.trackZMin() + 1 || worldZ == g.trackZMax() - 1) {
            railState = TrackPalette.RAIL;
        } else {
            railState = null;
        }
        if (railState != null) {
            BlockPos railPos = new BlockPos(worldX, g.railY(), worldZ);
            if (VSGameUtilsKt.getShipObjectManagingPos(level, railPos) == null) {
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
    private static void placePillarSlice(
        ServerLevel level,
        int worldX,
        TrackGeometry g,
        CarriageDims dims
    ) {
        int bedY = g.bedY();
        int topInclusive = bedY - 1;

        // Probe each Z column in the track span; take the minimum (deepest)
        // as the slice anchor. probeGroundY returns bedY as a sentinel for
        // "ship intercepts / no pillar needed" — skip those columns so a ship
        // above one edge doesn't force the whole slice to zero-height.
        int deepestGroundY = bedY;
        for (int z = g.trackZMin(); z <= g.trackZMax(); z++) {
            int ground = probeGroundY(level, worldX, z, bedY);
            if (ground >= bedY) continue;
            if (ground < deepestGroundY) deepestGroundY = ground;
        }
        int h = topInclusive - deepestGroundY + 1;
        if (h <= 0) return;

        Optional<BlockState[][]> topCol = PillarTemplateStore.getColumn(level, PillarSection.TOP, dims);
        Optional<BlockState[][]> midCol = PillarTemplateStore.getColumn(level, PillarSection.MIDDLE, dims);
        Optional<BlockState[][]> botCol = PillarTemplateStore.getColumn(level, PillarSection.BOTTOM, dims);

        int topH = PillarSection.TOP.height();
        int botH = PillarSection.BOTTOM.height();
        int placeBotH = Math.min(botH, h);
        int placeTopH = Math.min(topH, h - placeBotH);
        int placeMidH = h - placeBotH - placeTopH;

        int zMin = g.trackZMin();
        for (int z = g.trackZMin(); z <= g.trackZMax(); z++) {
            int zIdx = z - zMin;
            for (int i = 0; i < placeBotH; i++) {
                stampSliceCell(level, worldX, deepestGroundY + i, z, botCol, i, zIdx);
            }
            for (int i = 0; i < placeMidH; i++) {
                stampSliceCell(level, worldX, deepestGroundY + placeBotH + i, z, midCol, 0, zIdx);
            }
            for (int i = 0; i < placeTopH; i++) {
                int y = topInclusive - placeTopH + 1 + i;
                int row = (topH - placeTopH) + i;
                stampSliceCell(level, worldX, y, z, topCol, row, zIdx);
            }
        }
    }

    /**
     * Stamp one cell of a pillar slice. {@code column} is the unpacked
     * 2D template (row Y × Z offset) for the section, or empty for fallback.
     * Null template cells are treated as air — the world stays passable.
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
        Optional<BlockState[][]> column,
        int row,
        int zIdx
    ) {
        BlockPos pos = new BlockPos(x, y, z);
        if (VSGameUtilsKt.getShipObjectManagingPos(level, pos) != null) return;
        BlockState existing = level.getBlockState(pos);
        if (!isPassable(existing)) return;

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
        SilentBlockOps.setBlockSilent(level, pos, state);
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
        Optional<BlockState[][][]> trackTile = TrackTemplateStore.getCells(level, dims);

        for (int localX = 0; localX < 16; localX++) {
            int worldX = chunkMinX + localX;
            PillarSpec containingPillar = findPillarContaining(pillars, worldX);

            for (int worldZ = zLo; worldZ <= zHi; worldZ++) {
                placeTrackColumn(level, worldX, worldZ, g, trackTile);
            }

            if (containingPillar != null) {
                placePillarSlice(level, worldX, g, dims);
                if (worldX == containingPillar.centerX()) {
                    placeStairsBesidePillar(level, containingPillar, g);
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
     * <p>Selection rule (world-X based so placement is deterministic and
     * stable across chunk loads):</p>
     * <pre>
     *   stripIndex = floorDiv(centerX, BASE_PILLAR_SPACING)
     *   hasStairs  = stripIndex % 2 == 0       // every other pillar
     *   flipped    = floorDiv(stripIndex, 2) % 2 == 1  // alternating side
     * </pre>
     *
     * <p>Geometry: 3×8×3 template anchored with its top row at
     * {@code g.bedY() - 1} (same as the pillar top). 3 wide along X centred on
     * {@code centerX}. On the +Z side (non-flipped) the 3 outward Z columns
     * sit at {@code [trackZMax+1 .. trackZMax+3]}; on the -Z side (flipped)
     * at {@code [trackZMin-3 .. trackZMin-1]}, with {@link Mirror#LEFT_RIGHT}
     * so the step direction still runs "outward from track".</p>
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
        TrackGeometry g
    ) {
        int stripIndex = Math.floorDiv(pillar.centerX(), BASE_PILLAR_SPACING);
        if (Math.floorMod(stripIndex, 2) != 0) return;
        boolean flipped = Math.floorMod(Math.floorDiv(stripIndex, 2), 2) == 1;

        Optional<StructureTemplate> templateOpt =
            PillarTemplateStore.getAdjunct(level, games.brennan.dungeontrain.track.PillarAdjunct.STAIRS);
        if (templateOpt.isEmpty()) return;
        StructureTemplate template = templateOpt.get();

        int topInclusive = g.bedY() - 1;
        int originX = pillar.centerX() - 1; // centred 3-wide on centerX
        int originZ = flipped ? g.trackZMin() - STAIRS_Z : g.trackZMax() + 1;

        // Probe ground across the 3×3 stair footprint; anchor to the deepest.
        int deepestGroundY = g.bedY(); // sentinel "no ground found"
        for (int dx = 0; dx < STAIRS_X; dx++) {
            for (int dz = 0; dz < STAIRS_Z; dz++) {
                int ground = probeGroundY(level, originX + dx, originZ + dz, g.bedY());
                if (ground >= g.bedY()) continue;
                if (ground < deepestGroundY) deepestGroundY = ground;
            }
        }
        if (deepestGroundY >= g.bedY()) return;
        if (deepestGroundY > topInclusive) return;

        int currentTop = topInclusive;
        while (currentTop >= deepestGroundY) {
            int remaining = currentTop - deepestGroundY + 1;
            int copyHeight = Math.min(STAIRS_Y, remaining);
            int bottomY = currentTop - copyHeight + 1;

            // placeInWorld uses `origin` for the template's (0,0,0) corner.
            // We always want template Y=STAIRS_Y-1 to land at currentTop, so
            // origin Y = currentTop - (STAIRS_Y - 1). The BoundingBox clipping
            // below then discards cells whose world Y is outside
            // [bottomY, currentTop] — exactly the bottom rows on a partial copy.
            BlockPos copyOrigin = new BlockPos(originX, currentTop - (STAIRS_Y - 1), originZ);

            BoundingBox clip = new BoundingBox(
                originX, bottomY, originZ,
                originX + STAIRS_X - 1, currentTop, originZ + STAIRS_Z - 1
            );

            StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(true)
                .setBoundingBox(clip)
                .addProcessor(VSShipFilterProcessor.INSTANCE);
            if (flipped) settings.setMirror(Mirror.LEFT_RIGHT);

            template.placeInWorld(level, copyOrigin, copyOrigin, settings, level.getRandom(), 3);

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
