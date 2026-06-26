package games.brennan.dungeontrain.track;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.PillarTemplateStore;
import games.brennan.dungeontrain.editor.TrackTemplateStore;
import games.brennan.dungeontrain.template.GateContext;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.ShipFilterProcessor;
import games.brennan.dungeontrain.ship.Shipyard;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.tunnel.TunnelGenerator;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.tunnel.TunnelPlacer;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.world.StairsLocationData;
import games.brennan.dungeontrain.world.StairsRegistryData;
import games.brennan.dungeontrain.worldgen.FallingBlockAnchor;
import games.brennan.dungeontrain.worldgen.NetherFade;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import net.minecraft.core.Vec3i;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.joml.Vector3dc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
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
 * <p>Tall pillars (height ≥ {@link #TALL_PILLAR_HEIGHT_THRESHOLD}) fade out
 * toward their neighbours with a stepped corbel — the <em>arch taper</em> —
 * that hangs from the pillar top and steps up into the gap, so a span between
 * two pillars reads as a suggested archway rather than two bare posts. The
 * per-column step profile comes from {@link #archProfile(int)}; placement is
 * {@link #placeArchTaperWorldgen}.</p>
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
     * Pillars at or above this height get the taller arch taper
     * ({@code 5,3,2,1,1,1}) instead of the standard one ({@code 3,2,1,1}) —
     * an extra column nearest the pillar plus an extra one reaching further
     * into the gap. See {@link #archProfile(int)}.
     */
    private static final int TALL_ARCH_HEIGHT_THRESHOLD = 10;

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
    private static final int MIN_STAIRS_SPACING = 100;

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
     * Arch-taper profile for a pillar of the given gating {@code height}: the
     * per-column block counts stepping outward from each side of the pillar,
     * each column hanging from the pillar top ({@code bedY - 1}) downward.
     *
     * <ul>
     *   <li>{@code height < 5} → empty (short pillars get no arch).</li>
     *   <li>{@code 5 ≤ height ≤ 9} → {@code {3, 2, 1, 1}}.</li>
     *   <li>{@code height ≥ 10} → {@code {5, 3, 2, 1, 1, 1}} — an extra 5-tall
     *       column nearest the pillar plus an extra 1-tall column reaching one
     *       further into the gap.</li>
     * </ul>
     *
     * <p>Index 0 is the column immediately beside the pillar's footprint edge;
     * higher indices step further into the gap. The same profile is mirrored
     * onto both X sides. Pure + package-private for unit testing, matching
     * {@link #computeSpacing}/{@link #computeThickness}.</p>
     */
    static int[] archProfile(int height) {
        if (height < TALL_PILLAR_HEIGHT_THRESHOLD) return new int[0];
        if (height >= TALL_ARCH_HEIGHT_THRESHOLD) return new int[] {5, 3, 2, 1, 1, 1};
        return new int[] {3, 2, 1, 1};
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
        long tileIndex,
        // Second (Nether-dark) source for the per-block crossfade composite — null/absent for tiles
        // outside the crossfade, where {@link #resolveComposite} degrades to the Overworld variant.
        Optional<BlockState[][][]> netherCells,
        TrackVariantBlocks netherSidecar,
        ServerLevel fadeOverworld,
        long genSeed
    ) {
        /** Single-variant tile (no crossfade composite) — Nether source absent. */
        TilePaint(Optional<BlockState[][][]> cells, TrackVariantBlocks sidecar,
                  long worldSeed, long tileIndex) {
            this(cells, sidecar, worldSeed, tileIndex, Optional.empty(), null, null, 0L);
        }

        BlockState resolveSidecar(BlockState base, int xMod, int y, int zOff) {
            return resolveSidecar(sidecar, base, xMod, y, zOff);
        }

        private BlockState resolveSidecar(TrackVariantBlocks sc, BlockState base, int xMod, int y, int zOff) {
            if (sc == null || sc.isEmpty() || base == null) return base;
            BlockPos local = new BlockPos(xMod, y, zOff);
            games.brennan.dungeontrain.editor.VariantState picked = sc.resolve(
                local, worldSeed, (int) tileIndex);
            if (picked == null) return base;
            if (picked.isMob()) {
                TrackVariantMobs.warnDropped("tile", local, picked.entityId());
                return Blocks.AIR.defaultBlockState();
            }
            return games.brennan.dungeontrain.editor.RotationApplier.apply(
                picked.state(), picked.rotation(), picked.half(),
                local, worldSeed, (int) tileIndex,
                sc.lockIdAt(local));
        }

        /**
         * Final block for a cell, compositing the Overworld and Nether variants per block across the
         * Nether crossfade. {@code owBase} is the Overworld base cell the caller already read (NBT cell
         * or palette fallback). With no Nether source this is just the Overworld sidecar resolution
         * (unchanged behaviour). Inside the crossfade, cells {@link NetherFade#selectsNether} marks as
         * Nether take the Nether variant's block (its NBT cell + Nether sidecar), falling back to the
         * Overworld result when the Nether cell is empty so the dither never carves a hole.
         */
        BlockState resolveComposite(BlockState owBase, int xMod, int y, int zOff,
                                    int worldX, int worldY, int worldZ) {
            BlockState ow = resolveSidecar(owBase, xMod, y, zOff);
            if (fadeOverworld == null) return ow;          // no Nether source — single variant
            double ramp = NetherFade.rampAt(fadeOverworld, worldX);
            if (!NetherFade.selectsNether(genSeed, worldX, worldY, worldZ, ramp)) return ow;
            BlockState ntBase = (netherCells != null && netherCells.isPresent())
                ? netherCells.get()[xMod][y][zOff] : owBase;
            BlockState nt = resolveSidecar(netherSidecar, ntBase, xMod, y, zOff);
            return nt != null ? nt : ow;
        }
    }

    /**
     * Build the per-tile paint for track tile {@code idx}. Outside the Nether crossfade this is the
     * classic single registry-weighted variant (hard {@link TrainPhase} pick). Inside the crossfade it
     * also resolves the Nether variant ({@code nethertracks}) so {@link TilePaint#resolveComposite}
     * can dither the two per block. Shared by the runtime ({@code ensureTracksForChunk}) and worldgen
     * painters so both blend identically.
     */
    private static TilePaint buildTilePaint(ServerLevel level, CarriageDims dims, long worldSeed,
                                            long idx, Vec3i tileFootprint) {
        int tileX = (int) (idx * TrackPlacer.TILE_LENGTH);
        GateContext baseCtx = GateContext.atWorldX(level, tileX, dims.length());
        ServerLevel overworld = level.getServer().overworld();
        if (NetherFade.intersectsCrossfade(overworld, tileX, tileX + TrackPlacer.TILE_LENGTH - 1)) {
            long genSeed = DungeonTrainWorldData.get(overworld).getGenerationSeed();
            String owName = TrackVariantRegistry.pickName(TrackKind.TILE, worldSeed, idx,
                new GateContext(baseCtx.level(), TrainPhase.OVERWORLD));
            String ntName = TrackVariantRegistry.pickName(TrackKind.TILE, worldSeed, idx,
                new GateContext(baseCtx.level(), TrainPhase.NETHER));
            return new TilePaint(
                TrackTemplateStore.getCellsFor(level, dims, owName),
                TrackVariantBlocks.loadFor(TrackKind.TILE, owName, tileFootprint),
                worldSeed, idx,
                TrackTemplateStore.getCellsFor(level, dims, ntName),
                TrackVariantBlocks.loadFor(TrackKind.TILE, ntName, tileFootprint),
                overworld, genSeed);
        }
        String name = TrackVariantRegistry.pickName(TrackKind.TILE, worldSeed, idx, baseCtx);
        return new TilePaint(
            TrackTemplateStore.getCellsFor(level, dims, name),
            TrackVariantBlocks.loadFor(TrackKind.TILE, name, tileFootprint),
            worldSeed, idx);
    }

    private static boolean placeTrackColumn(
        ServerLevel level,
        int worldX,
        int worldZ,
        TrackGeometry g,
        TilePaint paint
    ) {
        return placeTrackColumn(level, null, worldX, worldZ, g, paint);
    }

    /**
     * Place the bed + rail of one track column from the authored tile {@code paint}.
     *
     * @param chunk when non-null, bed/rail writes go <b>section-local with no per-block relight</b>
     *              into this already-loaded chunk — the corridor-cleanup re-stamp path, which must be
     *              cheap ({@code level.setBlock}'s lighting measured ~84 ms/column on freshly-generating
     *              Nether-band chunks). {@code null} keeps the worldgen / fill-render-distance behaviour
     *              ({@link SilentBlockOps#setBlockSilent}, which relights). The caller guarantees every
     *              written position lies inside {@code chunk}.
     */
    private static boolean placeTrackColumn(
        ServerLevel level,
        @Nullable LevelChunk chunk,
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
        int xMod = Math.floorMod(worldX, TrackPlacer.TILE_LENGTH);

        BlockState bedState = paint.cells().isPresent()
            ? paint.cells().get()[xMod][0][zOff]
            : TrackPalette.BED;
        bedState = paint.resolveComposite(bedState, xMod, 0, zOff, worldX, g.bedY(), worldZ);
        if (bedState != null) {
            BlockState existingBed = level.getBlockState(bedPos);
            if (!existingBed.is(bedState.getBlock())) {
                writeTrackCell(level, chunk, bedPos, bedState);
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
        railState = paint.resolveComposite(railState, xMod, 1, zOff, worldX, g.railY(), worldZ);
        if (railState != null) {
            BlockPos railPos = new BlockPos(worldX, g.railY(), worldZ);
            if (!shipyard.isInShip(railPos)) {
                BlockState existingRail = level.getBlockState(railPos);
                if (!existingRail.is(railState.getBlock())) {
                    writeTrackCell(level, chunk, railPos, railState);
                }
            }
        }
        return true;
    }

    /**
     * Write one track cell. With a non-null {@code chunk}, writes section-local with {@code lightUpdate
     * = false} (no per-block relight, no shape cascade) plus a batched client {@code blockChanged} — the
     * cheap, Sable-safe path the corridor cleanup uses. With {@code chunk == null}, falls back to
     * {@link SilentBlockOps#setBlockSilent} (relights) for the worldgen / fill-render-distance callers.
     */
    private static void writeTrackCell(ServerLevel level, @Nullable LevelChunk chunk, BlockPos pos, BlockState state) {
        if (chunk == null) {
            SilentBlockOps.setBlockSilent(level, pos, state);
            return;
        }
        int sIdx = chunk.getSectionIndex(pos.getY());
        LevelChunkSection section = chunk.getSection(sIdx);
        int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
        int lx = pos.getX() & 15;
        int lz = pos.getZ() & 15;
        int ly = pos.getY() - baseY;
        if (section.getBlockState(lx, ly, lz).hasBlockEntity()) chunk.removeBlockEntity(pos);
        section.setBlockState(lx, ly, lz, state, false);
        chunk.setUnsaved(true);
        level.getChunkSource().blockChanged(pos);
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
            if (picked.isMob()) {
                TrackVariantMobs.warnDropped("pillar", local, picked.entityId());
                return Blocks.AIR.defaultBlockState();
            }
            return games.brennan.dungeontrain.editor.RotationApplier.apply(
                picked.state(), picked.rotation(), picked.half(),
                local, worldSeed, pillarIndex,
                sidecar.lockIdAt(local));
        }
    }

    private static PillarPaint loadPillarPaint(
        ServerLevel level, PillarSection section, CarriageDims dims, long worldSeed, int pillarIndex
    ) {
        TrackKind kind = PillarTemplateStore.pillarKind(section);
        // pillarIndex is the pillar's world-X — resolve its Diff-Level + phase for the gate.
        String name = TrackVariantRegistry.pickName(kind, worldSeed, pillarIndex,
            GateContext.atWorldX(level, pillarIndex));
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

        // Pre-compute pillar positions + heights in [chunkMinX - MIN_STAIRS_SPACING,
        // chunkMaxX + MIN_STAIRS_SPACING]. Used to apply the cross-chunk
        // stairs rules: (a) first pillar in MIN_STAIRS_SPACING-aligned slot,
        // (b) ≥ MIN_STAIRS_SPACING away from any prior short pillar
        // (height < SHORT_PILLAR_THRESHOLD). Each chunk reaches the same
        // verdict for any pillar in the chunk because the precompute extends
        // one full slot each side.
        java.util.NavigableMap<Integer, PillarInfo> nearbyPillars = new java.util.TreeMap<>();
        int scanMinX = chunkMinX - MIN_STAIRS_SPACING;
        int scanMaxX = chunkMaxX + MIN_STAIRS_SPACING;
        for (int x = scanMinX; x <= scanMaxX; x++) {
            int gy = probeGroundYWorldgen(level, x, probeZ, g.bedY());
            if (gy >= g.bedY()) continue;
            if (gy == voidSentinel) continue;
            int h = g.bedY() - 1 - gy;
            if (h < 0) continue;
            int sp = computeSpacing(h);
            if (Math.floorMod(x, sp) != 0) continue;
            nearbyPillars.put(x, new PillarInfo(gy, h));
        }

        for (int worldX = chunkMinX; worldX <= chunkMaxX; worldX++) {
            PillarInfo info = nearbyPillars.get(worldX);
            if (info == null) continue;
            int groundY = info.groundY();
            int height = info.height();
            int spacing = computeSpacing(height);
            int thickness = computeThickness(spacing);
            int minDx = -((thickness - 1) / 2);
            int maxDx = thickness / 2;
            for (int dx = minDx; dx <= maxDx; dx++) {
                placePillarSliceWorldgen(level, serverLevel, worldX + dx, g, dims, worldSeed, worldX);
            }

            // Arch taper — fade the pillar out toward each neighbour with a
            // stepped corbel hanging from the pillar top, built from the
            // pillar's OWN blocks extruded along X (keyed by the same centre,
            // so it picks the same variant). Placed AFTER the slab so the
            // slab's cells win where they overlap; the taper's isPassable
            // guard skips anything already solid. No-op for short pillars
            // (empty profile). Worldgen-only, same as the slab.
            placeArchTaperWorldgen(level, serverLevel, worldX + minDx, worldX + maxDx,
                height, g, dims, worldSeed, worldX);

            // Stairs eligibility — three independent gates, all must pass:
            //   (1) Don't put stairs on a short pillar itself — they'd sit
            //       barely above ground and look pointless.
            //   (2) First pillar in this MIN_STAIRS_SPACING-aligned slot.
            //       The lowerKey check + the boundary check together ensure
            //       no two stairs are placed within MIN_STAIRS_SPACING of
            //       each other (matches "never closer than 60").
            //   (3) ≥ MIN_STAIRS_SPACING away from the nearest prior short
            //       pillar (height < SHORT_PILLAR_THRESHOLD). Stops stairs
            //       from being plopped right next to a 1-2 block stub.
            // Stairs eligibility — Option C: cross-chunk registry. The
            // SavedData-backed StairsRegistryData persists pillar-side
            // stairs and short-pillar markers, so chunks beyond the ±1
            // chunk worldgen read window still see each other's commits.
            // tryReserveStairs is atomic: a parallel-thread chunk that
            // wants the same slot will lose the race and skip.
            ServerLevel ow = serverLevel.getServer().overworld();
            StairsRegistryData registry = StairsRegistryData.get(ow);

            if (height < SHORT_PILLAR_THRESHOLD) {
                // Record the short pillar so future stairs candidates honour
                // the MIN_STAIRS_SPACING exclusion against it across chunks.
                registry.recordShortPillar(worldX);
                continue;
            }

            Boolean reservedSide = registry.tryReserveStairs(worldX, MIN_STAIRS_SPACING);
            if (reservedSide == null) {
                LOGGER.debug("[stairs.elig] worldX={} reject=registry_conflict", worldX);
                continue;
            }

            LOGGER.info("[stairs.elig] worldX={} chunk=({},{}) PASS height={} side={} → calling placeStairs",
                worldX, chunkX, chunkZ, height, reservedSide ? "-Z" : "+Z");
            placeStairsBesidePillarWorldgen(level, serverLevel, worldX, groundY, g, worldSeed, reservedSide);
        }
    }

    /**
     * Pillars shorter than this height (in blocks) suppress nearby stairs
     * placement. A 1-2 block pillar is barely above ground and stairs
     * landing next to it would look out of place — better to skip stairs
     * in that stretch and let them land on a more substantial pillar.
     */
    private static final int SHORT_PILLAR_THRESHOLD = 3;

    /** Pillar position metadata cached during the worldgen scan. */
    private record PillarInfo(int groundY, int height) {}

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
     * Stamp the arch taper for one pillar: a stepped corbel on each X side that
     * hangs from the pillar top ({@code bedY - 1}) and steps up into the gap,
     * so adjacent pillars read as a suggested archway. Column block-counts come
     * from {@link #archProfile(int)} — index 0 sits immediately beside the
     * pillar footprint edge ({@code pillarMaxX + 1} / {@code pillarMinX - 1})
     * and higher indices step outward. Spans the full track width on Z,
     * matching the pillar slab. No-op for short pillars (empty profile).
     *
     * <p>The taper is built from the pillar's OWN blocks extruded along X: it
     * loads the same {@link PillarSection#TOP}/{@link PillarSection#MIDDLE}
     * paint the slab uses, keyed by {@code pillarCenterX} so it picks the same
     * variant. Each column is a vertical copy of the pillar face (cap on top),
     * truncated to the step height — so a themed/variant pillar fades out in
     * its own material rather than plain brick.</p>
     *
     * <p>Worldgen-only, mirroring {@link #placePillarSliceWorldgen}: no ship
     * checks (chunkgen precedes any ship). The taper overflows at most the
     * profile length past the pillar footprint — bounded well within the 3×3
     * decoration window, and a taper never reaches the adjacent pillar
     * (spacing ≥ 8 &gt; reach), so each taper is stamped exactly once by its
     * own pillar's chunk. Overlapping tapers and rising gap-terrain compose
     * correctly because {@link #stampArchColumnWorldgen} only writes passable
     * cells, yielding the per-column max height.</p>
     */
    private static void placeArchTaperWorldgen(
        WorldGenLevel level,
        ServerLevel serverLevel,
        int pillarMinX,
        int pillarMaxX,
        int height,
        TrackGeometry g,
        CarriageDims dims,
        long worldSeed,
        int pillarCenterX
    ) {
        int[] profile = archProfile(height);
        if (profile.length == 0) return;

        // Source the taper blocks from the pillar's own face — same paint,
        // same variant key — so the fade copies the pillar outward along X.
        // MIDDLE is only reached by a profile step taller than TOP.height()
        // (the leading 5 of the tall profile); shorter steps stay within TOP.
        PillarPaint top = loadPillarPaint(serverLevel, PillarSection.TOP, dims, worldSeed, pillarCenterX);
        PillarPaint mid = loadPillarPaint(serverLevel, PillarSection.MIDDLE, dims, worldSeed, pillarCenterX);

        int topInclusive = g.bedY() - 1;
        for (int step = 0; step < profile.length; step++) {
            int count = profile[step];
            // +X side steps right from the pillar's right edge; -X side steps
            // left from the left edge — symmetric mirror.
            stampArchColumnWorldgen(level, pillarMaxX + 1 + step, topInclusive, count, g, top, mid);
            stampArchColumnWorldgen(level, pillarMinX - 1 - step, topInclusive, count, g, top, mid);
        }
    }

    /**
     * Stamp one arch-taper column: {@code count} blocks hanging from
     * {@code topInclusive} downward across the full track Z width, each cell
     * copied from the pillar face via {@link #taperBlockAt}. Skips non-passable
     * cells so the taper embeds into any hill in the gap (and yields to an
     * already-placed pillar slab) instead of carving it, and skips authored
     * air cells so face cut-outs carry through. Worldgen write — no ship check.
     */
    private static void stampArchColumnWorldgen(
        WorldGenLevel level,
        int worldX,
        int topInclusive,
        int count,
        TrackGeometry g,
        PillarPaint top,
        PillarPaint mid
    ) {
        int zMin = g.trackZMin();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = g.trackZMin(); z <= g.trackZMax(); z++) {
            int zIdx = z - zMin;
            for (int i = 0; i < count; i++) {
                BlockState state = taperBlockAt(top, mid, i, zIdx);
                if (state == null) continue; // authored air cell — keep the gap
                pos.set(worldX, topInclusive - i, z);
                if (!isPassable(level.getBlockState(pos))) continue;
                level.setBlock(pos, state, Block.UPDATE_CLIENTS);
            }
        }
    }

    /**
     * Resolve the block the pillar face carries at depth {@code i} below the
     * top ({@code i = 0} is the cap row at {@code bedY - 1}) for track-width
     * index {@code zIdx}, so the taper is a faithful top-anchored copy of the
     * pillar. The top {@link PillarSection#TOP} rows come from the TOP paint
     * (cap downward); anything deeper repeats the MIDDLE paint, matching the
     * pillar's own {@code bottom→middles→top} stacking read from the top.
     * Returns {@code null} for an authored air cell (a pillar-face cut-out) so
     * the taper preserves the same gaps.
     */
    private static BlockState taperBlockAt(PillarPaint top, PillarPaint mid, int i, int zIdx) {
        int topH = PillarSection.TOP.height();
        if (i < topH) {
            return columnCell(top, topH - 1 - i, zIdx);
        }
        return columnCell(mid, 0, zIdx);
    }

    /**
     * Look up one cell of a pillar-section paint (template + sidecar),
     * mirroring the resolution in {@link #stampSliceCellWorldgen}. Returns the
     * fallback {@link TrackPalette#PILLAR} when no template is bound, or
     * {@code null} when the authored cell (or its sidecar override) is air.
     */
    private static BlockState columnCell(PillarPaint paint, int row, int zIdx) {
        Optional<BlockState[][]> column = paint.column();
        BlockState state;
        if (column.isPresent()
            && row >= 0 && row < column.get().length
            && zIdx >= 0 && zIdx < column.get()[row].length) {
            BlockState fromTemplate = column.get()[row][zIdx];
            if (fromTemplate == null) return null;
            state = fromTemplate;
        } else {
            state = TrackPalette.PILLAR;
        }
        return paint.resolveSidecar(state, row, zIdx);
    }

    /**
     * Worldgen-time stairs placement beside a pillar at {@code centerX}.
     * Stairs are exclusively a chunkgen feature — no runtime placement
     * exists. Skips ship checks (no ships exist during chunkgen), uses
     * {@code level.setBlock} + {@link Block#UPDATE_CLIENTS} for sidecar
     * writes.
     *
     * <p><b>Depth probe:</b> single-column probe at the stairs center
     * XZ, starting at {@code pillarBaseY} (= the pillar's lowest block).
     * If that block is solid → terrain extends at or above the pillar
     * base; scan UP for the first air row (hill rising past the pillar).
     * If passable → terrain is below; scan DOWN for the first solid
     * (cliff edge / down-slope). Anchor Y for stairs is one above the
     * found surface block. This replaces the previous 3×3 footprint
     * probe — the structure template's natural placement still skips
     * solid cells via the existing isPassable guard, so a single
     * representative probe at the center is sufficient.</p>
     *
     * <p>The window-parity rule for left/right alternation is unchanged
     * from the runtime version: even windows put stairs on +Z side, odd
     * windows on -Z. Stateless across chunk boundaries.</p>
     */
    private static void placeStairsBesidePillarWorldgen(
        WorldGenLevel level,
        ServerLevel serverLevel,
        int centerX,
        int pillarBaseY,
        TrackGeometry g,
        long worldSeed,
        boolean flipped
    ) {
        String stairsName = TrackVariantRegistry.pickName(
            TrackKind.ADJUNCT_STAIRS, worldSeed, centerX,
            GateContext.atWorldX(serverLevel, centerX));
        Optional<StructureTemplate> templateOpt =
            PillarTemplateStore.getAdjunctFor(serverLevel,
                games.brennan.dungeontrain.track.PillarAdjunct.STAIRS, stairsName);
        if (templateOpt.isEmpty()) {
            LOGGER.info("[stairs] candidate centerX={} reject=template_missing name={}", centerX, stairsName);
            return;
        }
        LOGGER.info("[stairs] candidate centerX={} pillarBaseY={} flipped={} template={}",
            centerX, pillarBaseY, flipped, stairsName);
        StructureTemplate template = templateOpt.get();
        TrackVariantBlocks stairsSidecar = TrackVariantBlocks.loadFor(
            TrackKind.ADJUNCT_STAIRS, stairsName,
            new Vec3i(STAIRS_X, STAIRS_Y, STAIRS_Z));

        int topInclusive = g.bedY() + 2;          // 3 rows above pillar top
        int originX = centerX - 1;                 // centred 3-wide on centerX
        int originZ = flipped ? g.trackZMin() - STAIRS_Z : g.trackZMax() + 1;

        // Single-column probe at stairs center XZ, anchored at pillar base Y.
        // Walk up if terrain is solid at that level (hill rising past the
        // pillar) or down if air (cliff edge / down-slope).
        int centerStairsZ = originZ + (STAIRS_Z - 1) / 2;
        int minY = level.getMinBuildHeight() + 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int deepestGroundY;
        pos.set(centerX, pillarBaseY, centerStairsZ);
        if (isPassable(level.getBlockState(pos))) {
            // Air at pillar base level — terrain is below. Walk down to
            // find the first non-passable block; anchor stairs one above.
            int found = -1;
            for (int y = pillarBaseY - 1; y >= minY; y--) {
                pos.set(centerX, y, centerStairsZ);
                if (!isPassable(level.getBlockState(pos))) { found = y; break; }
            }
            if (found < 0) {
                LOGGER.info("[stairs] centerX={} reject=void_below probeStart={}", centerX, pillarBaseY);
                return;
            }
            deepestGroundY = found + 1;
        } else {
            // Solid at pillar base level — terrain extends at or above.
            // Walk up until we find air; anchor stairs at that air block
            // (= one above the highest non-passable).
            int found = -1;
            for (int y = pillarBaseY + 1; y <= topInclusive + STAIRS_Y; y++) {
                pos.set(centerX, y, centerStairsZ);
                if (isPassable(level.getBlockState(pos))) { found = y; break; }
            }
            if (found < 0) {
                LOGGER.info("[stairs] centerX={} reject=terrain_above_cap probeStart={} topInclusive={}",
                    centerX, pillarBaseY, topInclusive);
                return;
            }
            deepestGroundY = found;
        }
        // Cap the descent at 2 blocks below sea level. Stairs in deep
        // water (e.g. ocean trench seafloor at y=30) would otherwise
        // place 30+ rows of template downward through the water column;
        // each row's water-block replacement is cheap on its own (worldgen
        // setBlock bypasses neighbor updates) but the stamp count adds up.
        // Capping at seaLevel-2 leaves stairs visibly anchored just under
        // the water surface and bounds the placement work to ~2 stamps
        // (16 rows) regardless of terrain depth.
        int seaLevel = level.getSeaLevel();
        int seaFloorCap = seaLevel - 2;
        if (deepestGroundY < seaFloorCap) {
            LOGGER.debug("[stairs] centerX={} clamping deepest {} → seaFloorCap {} (seaLevel={})",
                centerX, deepestGroundY, seaFloorCap, seaLevel);
            deepestGroundY = seaFloorCap;
        }

        // Only reject if the anchor is ABOVE the stair cap — placement
        // can't go anywhere useful from there. Anchor at bedY itself
        // (= terrain reaches up to train-bed level) is fine: stairs
        // occupy a thin [bedY, topInclusive] = 3-row band, the placement
        // loop's BoundingBox clips the template's top 3 rows, the
        // staircase visual sits above the local ground correctly.
        if (deepestGroundY > topInclusive) {
            LOGGER.info("[stairs] centerX={} reject=ground_above_stair_top deepest={} topInclusive={}",
                centerX, deepestGroundY, topInclusive);
            return;
        }
        LOGGER.info("[stairs] PLACED centerX={} deepestGroundY={} flipped={} side={}",
            centerX, deepestGroundY, flipped, flipped ? "-Z" : "+Z");

        // Index the placed footprint so the used_pillar_stairs advancement can
        // detect a player climbing these steps (StairsUsageEvents). Metadata
        // only — placement of the blocks above/below is unchanged.
        StairsLocationData.get(serverLevel).record(new StairsLocationData.Box(
            originX, deepestGroundY, originZ,
            originX + STAIRS_X - 1, topInclusive, originZ + STAIRS_Z - 1,
            StairsLocationData.Kind.PILLAR_STAIRS));

        stampStairsDescendingWorldgen(
            level, template, stairsSidecar,
            originX, originZ, topInclusive, deepestGroundY,
            flipped, worldSeed, centerX
        );
    }

    /**
     * Stamps the 3×8×3 stairs template repeatedly from {@code topInclusive}
     * down to {@code floorY}, forming a continuous descending staircase.
     * Each iteration places one 8-row stamp, clipped to fit the remaining
     * vertical extent. Shared between the up-stairs path
     * ({@link #placeStairsBesidePillarWorldgen}) and the down-stairs path
     * ({@link #placeDownStairsForTargets}).
     *
     * <p>Up-stairs: {@code topInclusive = bedY + 2}, {@code floorY} = ground
     * level below the pillar (terrain is open below).
     * Down-stairs: {@code topInclusive = surfaceY - 1}, {@code floorY =
     * bedY + 2} (terrain is solid above; caller must pre-carve the shaft).</p>
     *
     * <p>Mirror.LEFT_RIGHT negates local Z around stamp origin; shifting
     * origin by +(STAIRS_Z - 1) keeps the mirrored footprint at
     * [originZ .. originZ + STAIRS_Z - 1]. Same trick as the runtime
     * variant and as TunnelPlacer's portal mirror.</p>
     */
    private static void stampStairsDescendingWorldgen(
        WorldGenLevel level,
        StructureTemplate template,
        TrackVariantBlocks stairsSidecar,
        int originX, int originZ,
        int topInclusive, int floorY,
        boolean flipped,
        long worldSeed, int centerX
    ) {
        int stampOriginZ = !flipped ? originZ + STAIRS_Z - 1 : originZ;

        int currentTop = topInclusive;
        while (currentTop >= floorY) {
            int remaining = currentTop - floorY + 1;
            int copyHeight = Math.min(STAIRS_Y, remaining);
            int bottomY = currentTop - copyHeight + 1;

            BlockPos copyOrigin = new BlockPos(originX, currentTop - (STAIRS_Y - 1), stampOriginZ);

            BoundingBox clip = new BoundingBox(
                originX, bottomY, originZ,
                originX + STAIRS_X - 1, currentTop, originZ + STAIRS_Z - 1
            );

            StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(true)
                .setBoundingBox(clip)
                // Dry pillar stairs — don't inherit terrain water into waterloggable
                // blocks (see TunnelPlacer.stampTemplateWorldgen for the full rationale).
                .setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING);
            // No ShipFilterProcessor — no ships at chunkgen.
            if (!flipped) settings.setMirror(Mirror.LEFT_RIGHT);

            template.placeInWorld(level, copyOrigin, copyOrigin, settings, level.getRandom(), Block.UPDATE_CLIENTS);

            // Sidecar pass — overwrite flagged template-local positions
            // with the deterministic per-block pick. Mirror semantics
            // mirror the runtime variant. Block-entity NBT stamping is
            // skipped at worldgen (vanilla stairs/sign templates rarely
            // need it, and BE wiring through WorldGenLevel is awkward).
            if (!stairsSidecar.isEmpty()) {
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
                    games.brennan.dungeontrain.editor.VariantState picked =
                        stairsSidecar.resolve(entry.localPos(), worldSeed, centerX);
                    if (picked == null) continue;
                    if (picked.isMob()) {
                        TrackVariantMobs.warnDropped("stairs", entry.localPos(), picked.entityId());
                        level.setBlock(wpos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                        continue;
                    }
                    BlockState rotated = games.brennan.dungeontrain.editor.RotationApplier.apply(
                        picked.state(), picked.rotation(), picked.half(),
                        entry.localPos(), worldSeed, centerX,
                        stairsSidecar.lockIdAt(entry.localPos()));
                    level.setBlock(wpos, rotated, Block.UPDATE_CLIENTS);
                }
            }

            currentTop -= STAIRS_Y;
        }
    }

    /**
     * Reserved slot for a down-stair placement, produced by
     * {@link #precomputeDownStairsTargets} and consumed by
     * {@link #placeDownStairsForTargets}. The split exists because the
     * underground-qualification probe at {@code ceilingY+1} reads inside the
     * tunnel template's Y-extent — must read raw terrain BEFORE
     * {@link TunnelGenerator#placeTunnelSpaceAtWorldgen}; the carve+stamp
     * must run AFTER tunnel placement so the bottom 3 stair rows
     * (Y bedY+2..+4, inside the tunnel template footprint) win over
     * tunnel-template air cells.
     *
     * @param centerX   world X of the stair's central column.
     * @param flipped   {@code true} = -Z side (originZ = trackZMin - STAIRS_Z);
     *                  {@code false} = +Z side (originZ = trackZMax + 1).
     * @param surfaceY  Y of the first AIR block above the surface (the
     *                  block at surfaceY-1 is the topmost solid; the carved
     *                  shaft spans bedY+2..surfaceY-1 inclusive).
     */
    public record DownStairsTarget(int centerX, boolean flipped, int surfaceY) {}

    /**
     * Per-chunk precompute for the down-stairs placement pass. Probes the
     * raw terrain for underground qualification (using
     * {@link TunnelGenerator#isColumnUndergroundWorldgen}) at this X plus a
     * 5-block buffer on each side (keeps stairs away from tunnel portals).
     * Atomically reserves cross-chunk-safe spacing slots in
     * {@link StairsRegistryData} so up-stairs and down-stairs never cluster
     * within {@link #MIN_STAIRS_SPACING} blocks of each other.
     *
     * <p>Must be called BEFORE {@link TunnelGenerator#placeTunnelSpaceAtWorldgen}
     * in the same {@code Feature.place()} invocation — the
     * {@code ceilingY+1} probe reads inside the tunnel template's Y-extent
     * ({@code bedY..bedY+13}), so post-tunnel probes would see
     * tunnel-stamped blocks (which are not underground material) and
     * report false negatives.</p>
     *
     * @return list of reserved targets to place after tunnel generation
     *         finishes for this chunk. Empty list when no qualifying X
     *         columns exist (the common case for above-ground chunks).
     */
    public static List<DownStairsTarget> precomputeDownStairsTargets(
        WorldGenLevel level,
        ServerLevel serverLevel,
        int chunkX,
        int chunkZ,
        TrackGeometry g
    ) {
        if (isShipyardChunk(chunkX, chunkZ)) return Collections.emptyList();

        int chunkMinX = chunkX << 4;
        int chunkMaxX = chunkMinX + 15;
        int chunkMinZ = chunkZ << 4;
        int chunkMaxZ = chunkMinZ + 15;

        if (chunkMaxZ < g.trackZMin() || chunkMinZ > g.trackZMax()) return Collections.emptyList();

        int minBuildHeight = level.getMinBuildHeight();
        int maxBuildHeight = level.getMaxBuildHeight();
        if (g.bedY() < minBuildHeight || g.bedY() >= maxBuildHeight) return Collections.emptyList();

        int probeZ = g.trackCenterZ();
        if (probeZ < chunkMinZ || probeZ > chunkMaxZ) return Collections.emptyList();

        TunnelGeometry tg = TunnelGeometry.from(g);

        // Surface probe Y cap — 64 blocks above the tunnel ceiling. Matches
        // 8 stamp iterations (STAIRS_Y * 8 = 64) — well beyond typical
        // mountain heights and bounded against pathological terrain.
        int surfaceProbeCap = Math.min(g.bedY() + 64, maxBuildHeight - 1);
        int floorY = g.bedY() + 2;

        ServerLevel ow = serverLevel.getServer().overworld();
        StairsRegistryData registry = StairsRegistryData.get(ow);

        List<DownStairsTarget> targets = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int worldX = chunkMinX; worldX <= chunkMaxX; worldX++) {
            // Snap to tunnel section X edges — stairs land at the seam
            // between tunnel sections rather than mid-section. Tunnel
            // sections tile {@link TunnelPlacer#LENGTH}-cols flush along
            // X; pinning stair candidates to multiples of LENGTH makes the
            // shaft line up with section boundaries visually.
            if (Math.floorMod(worldX, TunnelPlacer.LENGTH) != 0) continue;
            // Center column must be underground. Cheap fast-reject for
            // most above-ground chunks (no further reads if false).
            if (!TunnelGenerator.isColumnUndergroundWorldgen(level, worldX, tg, false)) continue;
            // 5-block buffer on each side keeps the stair shaft away from
            // tunnel portals (which are stamped at run boundaries with a
            // ±~5-block pyramid that would clip the stair shaft). Reads
            // at worldX±5 stay well inside the ±16 worldgen 3×3 window.
            if (!TunnelGenerator.isColumnUndergroundWorldgen(level, worldX - 5, tg, false)) continue;
            if (!TunnelGenerator.isColumnUndergroundWorldgen(level, worldX + 5, tg, false)) continue;

            // Reserve first to determine the side. Surface Y depends on
            // {@code flipped} (we probe at the stair's actual centerZ on
            // the assigned side, not at the corridor center), so we need
            // the side before probing. Slot is consumed even if the probe
            // ultimately rejects — acceptable trade-off, see method
            // javadoc.
            Boolean reservedSide = registry.tryReserveStairs(worldX, MIN_STAIRS_SPACING);
            if (reservedSide == null) {
                LOGGER.debug("[downstairs.elig] worldX={} reject=registry_conflict", worldX);
                continue;
            }
            int centerStairsZ = downStairsCenterZ(reservedSide, g);

            // Probe surface Y at the stair's center XZ. Strict air check
            // (not isPassable) — skip past tree leaves, vines, and other
            // passable-but-solid-looking blocks so the shaft opens at
            // actual sky.
            int surfaceY = -1;
            for (int y = tg.ceilingY() + 1; y <= surfaceProbeCap; y++) {
                pos.set(worldX, y, centerStairsZ);
                if (level.getBlockState(pos).isAir()) { surfaceY = y; break; }
            }
            if (surfaceY < 0) {
                LOGGER.debug("[downstairs.elig] worldX={} reject=no_air_below_cap cap={}",
                    worldX, surfaceProbeCap);
                continue;
            }
            // Shaft must be at least SHORT_PILLAR_THRESHOLD + 2 tall to be
            // worth carving. Below that the staircase is shorter than the
            // template's one stamp and looks pointless.
            if (surfaceY - floorY < SHORT_PILLAR_THRESHOLD + 2) {
                LOGGER.debug("[downstairs.elig] worldX={} reject=shaft_too_short surfaceY={} floorY={}",
                    worldX, surfaceY, floorY);
                continue;
            }

            LOGGER.info("[downstairs.elig] worldX={} chunk=({},{}) PASS surfaceY={} side={}",
                worldX, chunkX, chunkZ, surfaceY, reservedSide ? "-Z" : "+Z");
            targets.add(new DownStairsTarget(worldX, reservedSide, surfaceY));
        }

        return targets;
    }

    /**
     * Consumes the {@link DownStairsTarget} list produced by
     * {@link #precomputeDownStairsTargets} and stamps each down-stairs
     * shaft + staircase. Must be called AFTER
     * {@link TunnelGenerator#placeTunnelSpaceAtWorldgen} in the same
     * {@code Feature.place()} invocation so the bottom 3 stair rows
     * overwrite the tunnel template's airspace cells (otherwise the tunnel
     * template's air stamps clobber the freshly placed stair blocks).
     *
     * <p>For each target:
     * <ol>
     *   <li>Load the stairs template + sidecar (reuses up-stairs
     *       {@code adjunct_stairs/*.nbt} — visually a descending staircase
     *       either direction).</li>
     *   <li>Carve a 3×height×3 air shaft from {@code bedY+2} up to
     *       {@code surfaceY-1}. This punches through the rock above the
     *       tunnel ceiling AND through the tunnel template's ceiling/wall
     *       at the stair Z columns.</li>
     *   <li>Anchor any sand/gravel directly at the shaft top (at
     *       {@code surfaceY}) so it converts to its stable equivalent and
     *       can't tick-fall into the shaft.</li>
     *   <li>Stamp the descending staircase via
     *       {@link #stampStairsDescendingWorldgen}.</li>
     * </ol>
     * </p>
     */
    public static void placeDownStairsForTargets(
        WorldGenLevel level,
        ServerLevel serverLevel,
        List<DownStairsTarget> targets,
        TrackGeometry g
    ) {
        if (targets.isEmpty()) return;
        long worldSeed = level.getSeed();
        for (DownStairsTarget target : targets) {
            placeDownStairsAtTarget(level, serverLevel, target, g, worldSeed);
        }
    }

    private static void placeDownStairsAtTarget(
        WorldGenLevel level,
        ServerLevel serverLevel,
        DownStairsTarget target,
        TrackGeometry g,
        long worldSeed
    ) {
        int centerX = target.centerX();
        boolean flipped = target.flipped();
        int surfaceY = target.surfaceY();

        String stairsName = TrackVariantRegistry.pickName(
            TrackKind.ADJUNCT_STAIRS, worldSeed, centerX,
            GateContext.atWorldX(serverLevel, centerX));
        Optional<StructureTemplate> templateOpt =
            PillarTemplateStore.getAdjunctFor(serverLevel,
                PillarAdjunct.STAIRS, stairsName);
        if (templateOpt.isEmpty()) {
            LOGGER.info("[downstairs] centerX={} reject=template_missing name={}", centerX, stairsName);
            return;
        }
        StructureTemplate template = templateOpt.get();
        TrackVariantBlocks stairsSidecar = TrackVariantBlocks.loadFor(
            TrackKind.ADJUNCT_STAIRS, stairsName,
            new Vec3i(STAIRS_X, STAIRS_Y, STAIRS_Z));

        int originX = centerX - 1;
        // Down-stairs originZ is one block FURTHER from the corridor than
        // up-stairs (see {@link #downStairsOriginZ}). The outermost stair
        // column lands ON the tunnel wall (Z = wallMaxZ/wallMinZ); the
        // carve breaks the wall there to form a doorway from the corridor
        // into the stair shaft.
        int originZ = downStairsOriginZ(flipped, g);
        int floorY = g.bedY() + 2;
        int topInclusive = surfaceY - 1;

        // Carve the shaft to air. Covers rock above the tunnel ceiling AND
        // the tunnel template's stamped ceiling/wall cells at the stair Z
        // columns (the bottom 3 rows of the carve sit inside the tunnel
        // template's Y footprint, so the tunnel's airspace stamp is also
        // overwritten — that's fine, all to air anyway, with the stair
        // stamp painting the actual stair blocks afterwards).
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < STAIRS_X; dx++) {
            for (int dz = 0; dz < STAIRS_Z; dz++) {
                for (int y = floorY; y <= topInclusive; y++) {
                    pos.set(originX + dx, y, originZ + dz);
                    level.setBlock(pos, air, Block.UPDATE_CLIENTS);
                }
            }
        }

        // Anchor any falling block directly above the shaft top. Probe is
        // at Y=surfaceY (the first AIR above the original surface) AND
        // Y=surfaceY+1 (defensive against 1-block air pockets the probe
        // may have anchored on). Both anchor calls are no-ops for air,
        // cheap for non-fallables; only sand/gravel pay the convert cost.
        for (int dx = 0; dx < STAIRS_X; dx++) {
            for (int dz = 0; dz < STAIRS_Z; dz++) {
                FallingBlockAnchor.anchorAtWorldgen(level,
                    new BlockPos(originX + dx, surfaceY, originZ + dz));
                FallingBlockAnchor.anchorAtWorldgen(level,
                    new BlockPos(originX + dx, surfaceY + 1, originZ + dz));
            }
        }

        LOGGER.info("[downstairs] PLACED centerX={} surfaceY={} floorY={} flipped={} side={}",
            centerX, surfaceY, floorY, flipped, flipped ? "-Z" : "+Z");

        stampStairsDescendingWorldgen(
            level, template, stairsSidecar,
            originX, originZ, topInclusive, floorY,
            flipped, worldSeed, centerX
        );

        // Above-ground entrance — visible marker showing where the stairs
        // emerge from the surface. Stamped after the stairs stamp so any
        // overlap (e.g. the stair template's topmost landing row at
        // Y=surfaceY-1) is preserved while the entrance frame above gets
        // its own clean stamp.
        stampDownStairsEntranceWorldgen(level, serverLevel, originX, originZ, surfaceY, flipped, worldSeed, centerX);

        // Index both the descending shaft and the surface entrance pavilion as
        // TUNNEL_STAIRS so used_tunnel_stairs fires whether the player enters
        // from the top or climbs up from the tunnel (StairsUsageEvents).
        // Metadata only — the stamped blocks are unchanged.
        StairsLocationData index = StairsLocationData.get(serverLevel);
        index.record(new StairsLocationData.Box(
            originX, floorY, originZ,
            originX + STAIRS_X - 1, topInclusive, originZ + STAIRS_Z - 1,
            StairsLocationData.Kind.TUNNEL_STAIRS));
        int entranceMinX = originX - 1;
        int entranceMinZ = originZ - 1;
        int entranceBaseY = surfaceY - ENTRANCE_OVERLAP_Y;
        index.record(new StairsLocationData.Box(
            entranceMinX, entranceBaseY, entranceMinZ,
            entranceMinX + PillarAdjunct.STAIRS_ENTRANCE.xSize() - 1,
            entranceBaseY + PillarAdjunct.STAIRS_ENTRANCE.ySize() - 1,
            entranceMinZ + PillarAdjunct.STAIRS_ENTRANCE.zSize() - 1,
            StairsLocationData.Kind.TUNNEL_STAIRS));
    }

    /**
     * X-origin offset for down-stair placement on the assigned side.
     * Down-stairs sit one block FURTHER from the corridor than up-stairs
     * (compare with the inline formula in
     * {@link #placeStairsBesidePillarWorldgen}, which uses
     * {@code trackZMin - STAIRS_Z} / {@code trackZMax + 1}). Pushing them
     * outward by one block aligns the outermost stair column with the
     * tunnel wall (Z = wallMinZ/wallMaxZ): the carve breaks the wall at
     * that Z to form a "doorway" gap that the player walks through when
     * exiting the staircase into the corridor.
     */
    private static int downStairsOriginZ(boolean flipped, TrackGeometry g) {
        return flipped ? g.trackZMin() - STAIRS_Z - 1 : g.trackZMax() + 2;
    }

    /** Center Z of the 3-wide down-stair footprint on the assigned side. */
    private static int downStairsCenterZ(boolean flipped, TrackGeometry g) {
        return downStairsOriginZ(flipped, g) + (STAIRS_Z - 1) / 2;
    }

    /**
     * Vertical overlap between the down-stair entrance template and the top
     * of the stair shaft. The bottom {@code ENTRANCE_OVERLAP_Y} rows of the
     * entrance occupy the same Y range as the top {@code ENTRANCE_OVERLAP_Y}
     * stair steps — lets the entrance template author blend the staircase
     * into the entrance interior (e.g. a landing pad at the top of the
     * stairs).
     */
    private static final int ENTRANCE_OVERLAP_Y = 2;

    /**
     * Above-ground entrance for a down-stair shaft. Footprint is
     * {@link PillarAdjunct#STAIRS_ENTRANCE} (5×8×5), centered on the 3×3
     * shaft. Y origin is {@code surfaceY - ENTRANCE_OVERLAP_Y} so the
     * entrance's bottom {@code ENTRANCE_OVERLAP_Y} rows overlap with the
     * top of the staircase (allowing the template to include a landing or
     * threshold blended into the stair top).
     *
     * <p>Tries the NBT-loaded template first (editor-authored, picked via
     * {@link TrackVariantRegistry#pickName}). If no template is registered,
     * falls back to a code-based default: a stone-brick tower with a
     * 1×2 doorway cut into the outer Z wall at player walking height
     * (above the overlap region).</p>
     */
    private static void stampDownStairsEntranceWorldgen(
        WorldGenLevel level,
        ServerLevel serverLevel,
        int originX, int originZ, int surfaceY,
        boolean flipped,
        long worldSeed, int centerX
    ) {
        // 5×5 footprint centered on the 3×3 shaft.
        int minX = originX - 1;
        int minZ = originZ - 1;
        // Drop the entrance ENTRANCE_OVERLAP_Y blocks so its bottom rows
        // sit inside the top of the staircase shaft.
        int entranceBaseY = surfaceY - ENTRANCE_OVERLAP_Y;

        String entranceName = TrackVariantRegistry.pickName(
            TrackKind.ADJUNCT_STAIRS_ENTRANCE, worldSeed, centerX,
            GateContext.atWorldX(serverLevel, centerX));
        Optional<StructureTemplate> templateOpt =
            PillarTemplateStore.getAdjunctFor(serverLevel,
                PillarAdjunct.STAIRS_ENTRANCE, entranceName);
        if (templateOpt.isPresent()) {
            StructureTemplate template = templateOpt.get();
            TrackVariantBlocks sidecar = TrackVariantBlocks.loadFor(
                TrackKind.ADJUNCT_STAIRS_ENTRANCE, entranceName,
                new Vec3i(PillarAdjunct.STAIRS_ENTRANCE.xSize(),
                          PillarAdjunct.STAIRS_ENTRANCE.ySize(),
                          PillarAdjunct.STAIRS_ENTRANCE.zSize()));
            // Mirror across Z so an "outer-side doorway" authored on +Z
            // also lands on the -Z side when flipped.
            int stampOriginZ = !flipped ? minZ + PillarAdjunct.STAIRS_ENTRANCE.zSize() - 1 : minZ;
            BlockPos stampOrigin = new BlockPos(minX, entranceBaseY, stampOriginZ);
            StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(true)
                // Dry pillar stairs — don't inherit terrain water into waterloggable
                // blocks (see TunnelPlacer.stampTemplateWorldgen for the full rationale).
                .setLiquidSettings(LiquidSettings.IGNORE_WATERLOGGING);
            if (!flipped) settings.setMirror(Mirror.LEFT_RIGHT);
            template.placeInWorld(level, stampOrigin, stampOrigin, settings, level.getRandom(), Block.UPDATE_CLIENTS);
            // Sidecar pass — same shape as stairs stamp.
            if (!sidecar.isEmpty()) {
                for (var entry : sidecar.entries()) {
                    int lx = entry.localPos().getX();
                    int ly = entry.localPos().getY();
                    int lz = entry.localPos().getZ();
                    int wx = minX + lx;
                    int wy = entranceBaseY + ly;
                    int wz = flipped ? (minZ + lz) : (minZ + PillarAdjunct.STAIRS_ENTRANCE.zSize() - 1 - lz);
                    BlockPos wpos = new BlockPos(wx, wy, wz);
                    games.brennan.dungeontrain.editor.VariantState picked =
                        sidecar.resolve(entry.localPos(), worldSeed, centerX);
                    if (picked == null) continue;
                    if (picked.isMob()) {
                        TrackVariantMobs.warnDropped("stairs_entrance", entry.localPos(), picked.entityId());
                        level.setBlock(wpos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                        continue;
                    }
                    BlockState rotated = games.brennan.dungeontrain.editor.RotationApplier.apply(
                        picked.state(), picked.rotation(), picked.half(),
                        entry.localPos(), worldSeed, centerX,
                        sidecar.lockIdAt(entry.localPos()));
                    level.setBlock(wpos, rotated, Block.UPDATE_CLIENTS);
                }
            }
            LOGGER.info("[downstairs.entrance] centerX={} NBT placed name={} flipped={}",
                centerX, entranceName, flipped);
            return;
        }

        // Fallback: code-based stone-brick tower with a 1×2 doorway on the
        // outer Z wall at player walking height (above the overlap region).
        LOGGER.debug("[downstairs.entrance] centerX={} no NBT template; using code-based default", centerX);
        BlockState brick = Blocks.STONE_BRICKS.defaultBlockState();
        int maxX = originX + STAIRS_X;       // = originX + 3 → 5 wide inclusive
        int maxZ = originZ + STAIRS_Z;       // = originZ + 3 → 5 wide inclusive
        int doorwayX = originX + 1;          // = middle X of 5×5 = shaft centerX
        int doorwayZ = flipped ? minZ : maxZ;
        // Doorway sits at the player's walking height above the surface,
        // which is at dy = ENTRANCE_OVERLAP_Y (= world Y surfaceY) and
        // dy = ENTRANCE_OVERLAP_Y + 1 (= player head).
        int doorwayDyLo = ENTRANCE_OVERLAP_Y;
        int doorwayDyHi = ENTRANCE_OVERLAP_Y + 1;
        int ySize = PillarAdjunct.STAIRS_ENTRANCE.ySize();
        for (int dy = 0; dy < ySize; dy++) {
            int y = entranceBaseY + dy;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean isPerimeter =
                        x == minX || x == maxX || z == minZ || z == maxZ;
                    if (!isPerimeter) continue;
                    if (x == doorwayX && z == doorwayZ && (dy == doorwayDyLo || dy == doorwayDyHi)) continue;
                    level.setBlock(new BlockPos(x, y, z), brick, Block.UPDATE_CLIENTS);
                }
            }
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

        // Bail if the chunk isn't yet at FULL ChunkStatus — same root
        // cause as the tunnel probe fix (commit 9b39b52). hasChunk in
        // fillRenderDistance returns true for chunks at any holder
        // level, but level.getBlockState inside placeTrackColumn would
        // sync-load a not-yet-FULL chunk. Observed: 500ms tracks=
        // spikes when flying over freshly-streaming chunks. Returning
        // here keeps the chunk in pending; the next 10-tick sweep
        // retries once it's FULL.
        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) return;

        int zLo = Math.max(g.trackZMin(), chunkMinZ);
        int zHi = Math.min(g.trackZMax(), chunkMaxZ);

        // Fetch per-world dims once; the track-template store validates
        // against this, and the call is cheap (SavedData lookup).
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();

        // Per-tile paint cache (≤ 5 tiles touch any single 16-wide chunk so
        // this map stays tiny). Key = tileIndex (X / TILE_LENGTH); value =
        // the registry-picked name's cells + per-block sidecar bundle.
        long worldSeed = level.getSeed();
        Vec3i tileFootprint = TrackKind.TILE.dims(dims);
        Map<Long, TilePaint> tilePaints = new HashMap<>();

        // Pillar placement is worldgen-only now (see TrackBedFeature →
        // placePillarsAtWorldgen). Runtime ensureTracksForChunk runs only
        // for chunks that loaded without TrackBedFeature having fired
        // (legacy worlds, or chunks generated before our biome modifier
        // was attached). Those chunks get tracks but no pillars — visual
        // regression accepted in exchange for eliminating the fluid-update
        // cascade that fired when runtime pillar slices replaced water
        // blocks under the corridor. Side stairs go away with the pillars
        // for the same reason; reintroduce both at worldgen time if needed.
        for (int localX = 0; localX < 16; localX++) {
            int worldX = chunkMinX + localX;
            long tileIndex = Math.floorDiv((long) worldX, (long) TrackPlacer.TILE_LENGTH);
            TilePaint paint = tilePaints.computeIfAbsent(
                tileIndex,
                idx -> buildTilePaint(level, dims, worldSeed, idx, tileFootprint)
            );

            for (int worldZ = zLo; worldZ <= zHi; worldZ++) {
                placeTrackColumn(level, worldX, worldZ, g, paint);
            }
        }

        filledChunks.add(chunkKey);
    }

    /**
     * Re-stamp the AUTHORED track template (bed + rails + per-block variant sidecar — never the
     * {@link TrackPalette} fallback) for ONLY the given track cells, each a packed
     * {@code (worldX << 32) | (worldZ & 0xFFFFFFFF)} long. The corridor cleanup uses this to repair
     * just the handful of bed/rail cells a cross-chunk Nether-decoration column buried, instead of
     * re-laying the whole 16-wide chunk via {@link #ensureTracksForChunk} (measured at 0.5–1.4 s when
     * the per-column {@code Shipyards}/{@code getBlockState}/template work ran for all ~80 columns).
     * Each touched tile's paint is resolved once (cached {@link TrackTemplateStore} lookups). Caller
     * is responsible for only passing cells it actually cleared to air on the track row.
     */
    public static void restampTrackColumns(ServerLevel level, LevelChunk chunk, TrackGeometry g, Set<Long> packedColumns) {
        if (packedColumns.isEmpty()) return;
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        long worldSeed = level.getSeed();
        Vec3i tileFootprint = TrackKind.TILE.dims(dims);
        Map<Long, TilePaint> tilePaints = new HashMap<>();
        for (long key : packedColumns) {
            int worldX = (int) (key >> 32);
            int worldZ = (int) key;
            long tileIndex = Math.floorDiv((long) worldX, (long) TrackPlacer.TILE_LENGTH);
            TilePaint paint = tilePaints.computeIfAbsent(
                tileIndex,
                idx -> {
                    String name = TrackVariantRegistry.pickName(TrackKind.TILE, worldSeed, idx,
                        GateContext.atWorldX(level, (int) (idx * TrackPlacer.TILE_LENGTH), dims.length()));
                    Optional<BlockState[][][]> cells = TrackTemplateStore.getCellsFor(level, dims, name);
                    TrackVariantBlocks sidecar =
                        TrackVariantBlocks.loadFor(TrackKind.TILE, name, tileFootprint);
                    return new TilePaint(cells, sidecar, worldSeed, idx);
                }
            );
            placeTrackColumn(level, chunk, worldX, worldZ, g, paint);
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
            long tileIndex = Math.floorDiv((long) x, (long) TrackPlacer.TILE_LENGTH);
            int xMod = Math.floorMod(x, TrackPlacer.TILE_LENGTH);
            TilePaint paint = tilePaints.computeIfAbsent(
                tileIndex,
                idx -> buildTilePaint(serverLevel, dims, worldSeed, idx, tileFootprint)
            );

            for (int z = zLo; z <= zHi; z++) {
                int zOff = z - g.trackZMin();

                // Bed row (template y=0). null cells = author-authored air —
                // write air explicitly so terrain doesn't show through the gap.
                BlockState bedState = paint.cells().isPresent()
                    ? paint.cells().get()[xMod][0][zOff]
                    : TrackPalette.BED;
                bedState = paint.resolveComposite(bedState, xMod, 0, zOff, x, g.bedY(), z);
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
                railState = paint.resolveComposite(railState, xMod, 1, zOff, x, g.railY(), z);
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
                    // Anchor the cell directly above the carve so that any
                    // sand/gravel column resting on the now-air ceiling
                    // doesn't fall onto the rails when its persisted
                    // worldgen-scheduled tick fires post-load.
                    int anchorY = clearMaxY + 1;
                    if (anchorY < maxBuildHeight) {
                        FallingBlockAnchor.anchorAtWorldgen(level, new BlockPos(x, anchorY, z));
                    }
                }
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
