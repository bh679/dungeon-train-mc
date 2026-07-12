package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.world.StartingDimension;
import games.brennan.dungeontrain.worldgen.NetherBand;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Predicate;

/**
 * Sweeps cross-chunk foliage spillover out of the train corridor on a
 * deferred per-tick drain. Trees rooted in chunk X+1 can place leaves
 * up to ~16 blocks into chunk X during X+1's {@code vegetal_decoration}
 * step, which fires AFTER chunk X has already finished its
 * {@code top_layer_modification} (where {@code TrackBedFeature} runs).
 * The spillover lands inside the cleared corridor without re-clearing.
 *
 * <p>Architecture: enqueue corridor chunks on {@link ChunkEvent.Load},
 * drain up to {@link #MAX_CHUNKS_PER_TICK} chunks per server tick (scaled to the
 * pending backlog by {@link #drainBudget(int)}). Setting blocks
 * synchronously inside the load callback was tried first and hung the
 * server thread because lighting / fluid cascades re-entered chunk
 * loading recursively. Deferring to a tick boundary breaks that path —
 * setBlock happens after every chunk-load callback in flight has
 * returned.</p>
 *
 * <p>Predicate is intentionally narrow: leaves, vines, saplings, small
 * + tall flowers. Explicitly NOT water, lava, fire, snow, bubble columns,
 * or any other replaceable block — those would cascade. Solid blocks
 * (logs, stone, dirt) above the carriage envelope are out of scope; they
 * are tree trunks that legitimately stand inside the corridor's
 * horizontal footprint above the carriage roof, and the train doesn't
 * physically interact with them anyway.</p>
 *
 * <p><b>Nether-band clutter sweep.</b> Inside the overworld Nether transition band's core
 * ({@link NetherBand#isInNetherBiome}) the corridor additionally collects basalt / blackstone /
 * magma / nylium / wart-block etc. that the real Nether decoration features
 * ({@code NetherTransitionFeature.decorateCoreChunkWithNetherFeatures}) spill across chunk
 * boundaries — a basalt column rooted in chunk X+1 writes into chunk X's tunnel <em>after</em>
 * worldgen's in-chunk {@code clearCorridorClearance} + {@code track_bed} carve already ran, the
 * same late-spillover path foliage takes. For those columns we widen the sweep to the full tunnel
 * airspace ({@code TunnelGeometry.airMinZ..airMaxZ}) across {@code bedY..ceilingY-1} and remove
 * {@link #isNetherClutter Nether terrain/decoration}.
 *
 * <p>That Y span deliberately reaches the <b>bed/rail row</b> ({@code bedY} + {@code railY}), which
 * the airspace-only clearances skip. Cross-chunk timing lets a neighbouring basalt feature overwrite
 * the bed (or a rail) <em>after</em> this chunk's {@code track_bed} already laid it, and no other pass
 * repairs that — every clearance protects the track row by starting at {@code bedY+1}. All clutter is
 * cleared to air; if any sat on the track row ({@link #isTrackRowCell}) the <b>authored track template</b>
 * ({@code TrackGenerator.restampTrackColumns} — the real {@code TrackTemplateStore} cells + per-block
 * variant sidecar, never the hardcoded fallback) is re-stamped over just those columns to put the solid bed/rails back,
 * so the repaired track matches exactly what worldgen laid. Fluids stay excluded (the cascade hazard
 * above); worldgen-placed water is drained at generation time by {@code NetherTransitionFeature}
 * instead. The stone-brick walls sit outside {@code airMinZ..airMaxZ}, so they're never touched.</p>
 */
public final class CorridorCleanupEvents {

    /**
     * Upper bound on corridor chunks cleaned per server tick. The actual budget is
     * {@link #drainBudget(int)} of the pending-queue depth, so a burst of freshly generated corridor
     * chunks clears within a tick or two of loading — ahead of the train — instead of trickling out
     * one per tick (the old fixed budget let the train outrun the sweep, so cross-chunk basalt reached
     * the rails before it was cleared). Each chunk scan is a narrow bounded region (~a couple thousand
     * block reads), so 16/tick stays well under a millisecond and the queue normally sits near-empty.
     */
    private static final int MAX_CHUNKS_PER_TICK = 16;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /**
     * Wall-clock cap on a single drain pass (applied only AFTER at least one chunk is processed, so
     * forward progress is guaranteed). Block I/O + the track re-stamp dominate per-chunk cost in the
     * Nether band, and the count cap alone let a 16-chunk burst freeze the server tick for seconds;
     * this bounds the freeze and spreads the rest across ticks. Un-drained chunks stay in
     * {@link #PENDING}, {@code CLEANED}-unmarked, so they're still swept before the train reaches them.
     * {@link #shouldStopDraining} is pure — unit-tested.
     */
    private static final long DRAIN_BUDGET_NANOS = 3_000_000L; // ~3 ms

    /** Foliage / Nether-clutter strip predicates, pre-resolved so the per-cell sweep allocates nothing. */
    private static final Predicate<BlockState> FOLIAGE = CorridorCleanupEvents::isFoliage;
    private static final Predicate<BlockState> CLUTTER = CorridorCleanupEvents::isNetherClutter;

    /** ChunkPos longs that have been queued by {@link #onChunkLoad}, awaiting drain. */
    private static final Deque<Long> PENDING = new ConcurrentLinkedDeque<>();

    /** ChunkPos longs already cleaned this session. In-memory only. */
    private static final Set<Long> CLEANED = ConcurrentHashMap.newKeySet();

    private CorridorCleanupEvents() {}

        public static void onChunkLoad(net.minecraft.world.level.LevelAccessor chunkLevel, net.minecraft.world.level.chunk.ChunkAccess loadedChunk, boolean newChunk) {
        if (!(chunkLevel instanceof ServerLevel level)) return;

        MinecraftServer server = level.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        StartingDimension expected = data.startingDimension();
        if (!level.dimension().equals(expected.levelKey())) return;

        ChunkAccess chunk = loadedChunk;
        ChunkPos pos = chunk.getPos();
        int cx = pos.x;
        int cz = pos.z;
        if (TrackGenerator.isShipyardChunk(cx, cz)) return;

        // Fast Z-corridor prefilter — enqueue any chunk overlapping the tunnel AIRSPACE span
        // (airMinZ..airMaxZ = trackZ ± 3), not just the track-Z rows. The Nether-clutter sweep cleans
        // the full airspace, so when the corridor straddles a chunk Z-boundary the chunk holding only
        // the outer ±3 corridor pad must still be enqueued — otherwise its pad basalt is never swept.
        // The per-chunk sweeps clamp their own Z ranges, so the wider gate is safe (the foliage sweep
        // simply no-ops on a pad-only chunk).
        CarriageDims dims = data.dims();
        TrackGeometry g = TrackGeometry.from(dims, data.getTrainY());
        TunnelGeometry tg = TunnelGeometry.from(g);
        int chunkMinZ = cz << 4;
        int chunkMaxZ = chunkMinZ + 15;
        if (!chunkOverlapsCorridorZ(chunkMinZ, chunkMaxZ, tg.airMinZ(), tg.airMaxZ())) return;

        // A chunk's decoration spills basalt into its NEIGHBOURS' corridor during that chunk's feature
        // step — which can run AFTER a neighbour already loaded, got swept, and was marked CLEANED. The
        // late spillover then never gets re-swept and surfaces just ahead of where cleanup already passed
        // (a basalt-deltas column rooted in the chunk ahead bleeding back into the one you're riding into).
        // Fix: on every corridor-chunk load, clear the CLEANED mark on this chunk AND its 8 neighbours and
        // (re)enqueue them, so the chunk that just decorated triggers a re-sweep of the cells it spilled
        // into. The drain (16 chunks/tick — far above the load rate) skips still-unloaded neighbours (they
        // re-enqueue on their own load), and re-sweeping a clean chunk is a cheap no-op scan.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int nx = cx + dx, nz = cz + dz;
                if (TrackGenerator.isShipyardChunk(nx, nz)) continue;
                int nMinZ = nz << 4;
                if (!chunkOverlapsCorridorZ(nMinZ, nMinZ + 15, tg.airMinZ(), tg.airMaxZ())) continue;
                long nKey = ChunkPos.asLong(nx, nz);
                CLEANED.remove(nKey);
                PENDING.offer(nKey);
            }
        }
    }

        public static void onLevelTick(net.minecraft.world.level.Level tickedLevel) {
        if (!(tickedLevel instanceof ServerLevel level)) return;
        if (PENDING.isEmpty()) return;

        // Resolve corridor geometry once per tick. If data isn't ready yet
        // (very early world creation), bail — pending chunks stay queued.
        MinecraftServer server = level.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!level.dimension().equals(data.startingDimension().levelKey())) return;

        CarriageDims dims = data.dims();
        TrackGeometry g = TrackGeometry.from(dims, data.getTrainY());

        // Resolve the band layout ONCE per drain — it is constant within a tick, so the per-column
        // core test (NetherBand.isInNetherBiome(cycle, x)) pays no fromConfig / SavedData lookups.
        long bandStartX = NetherBand.startX(overworld);
        WorldGenCycle cycle = WorldGenCycle.fromConfig();

        int budget = drainBudget(PENDING.size());
        long drainStartNanos = System.nanoTime();
        while (budget > 0) {
            Long key = PENDING.poll();
            if (key == null) break;
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            if (CLEANED.contains(key)) continue;
            // getChunkNow returns null for a not-yet-FULL (or unloaded) chunk — unlike hasChunk, which
            // would let level.getBlockState sync-load it (an observed multi-100ms stall). Drop it; a
            // neighbour load re-enqueues it once it is FULL.
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) continue;
            cleanCorridorChunk(chunk, level, cx, cz, dims, g, cycle, bandStartX);
            CLEANED.add(key);
            budget--;
            // Wall-clock cap (only after >=1 chunk, so forward progress is guaranteed): keep a single
            // tick from freezing on a burst of expensive fresh chunks. The rest stay queued.
            if (shouldStopDraining(System.nanoTime() - drainStartNanos)) break;
        }
    }

    /**
     * Per-tick drain budget: clean as many queued corridor chunks as are pending, capped at
     * {@link #MAX_CHUNKS_PER_TICK}. Scaling with the backlog lets a burst of freshly generated chunks
     * clear within a tick or two (so the train never rides into un-swept basalt), while the cap bounds
     * worst-case per-tick work. Pure function — unit-tested.
     */
    static int drainBudget(int pendingSize) {
        return Math.min(Math.max(pendingSize, 0), MAX_CHUNKS_PER_TICK);
    }

    /**
     * Whether the per-tick drain has spent its wall-clock slice ({@link #DRAIN_BUDGET_NANOS}) and
     * should stop after the current chunk. The caller consults this only after draining at least one
     * chunk, so the sweep always makes forward progress. Pure function — unit-tested.
     */
    static boolean shouldStopDraining(long elapsedNanos) {
        return elapsedNanos >= DRAIN_BUDGET_NANOS;
    }

    /**
     * Whether a chunk's Z span [{@code chunkMinZ}..{@code chunkMaxZ}] overlaps the corridor Z span
     * [{@code corridorZMin}..{@code corridorZMax}]. Used as the {@link #onChunkLoad} enqueue prefilter
     * against the tunnel airspace span, so a chunk holding only the outer corridor pad (when the
     * corridor straddles a chunk Z-boundary) is never dropped. Pure function — unit-tested.
     */
    static boolean chunkOverlapsCorridorZ(int chunkMinZ, int chunkMaxZ, int corridorZMin, int corridorZMax) {
        return chunkMaxZ >= corridorZMin && chunkMinZ <= corridorZMax;
    }

    private static void cleanCorridorChunk(
        LevelChunk chunk, ServerLevel level, int cx, int cz, CarriageDims dims, TrackGeometry g,
        WorldGenCycle cycle, long bandStartX
    ) {
        int chunkMinX = cx << 4;
        int chunkMinZ = cz << 4;
        int chunkMaxZ = chunkMinZ + 15;

        // Foliage sweep across the track Z-span + carriage envelope (all dimensions). The Y range
        // covers the bed + rail rows AND the carriage envelope, so cross-chunk foliage spillover into
        // template-authored air gaps in those rows also gets cleaned; the predicate is narrow enough
        // that the bed and rail blocks themselves stay intact.
        int zLoF = Math.max(g.trackZMin(), chunkMinZ);
        int zHiF = Math.min(g.trackZMax(), chunkMaxZ);
        int trainY = g.bedY() + 2;
        int minYF = Math.max(level.getMinBuildHeight(), g.bedY());
        int maxYF = Math.min(level.getMaxBuildHeight() - 1, trainY + dims.height());
        if (zLoF <= zHiF && maxYF >= minYF) {
            sweepSection(chunk, level, chunkMinX, zLoF, zHiF, minYF, maxYF, null, FOLIAGE, g, null);
        }

        // Nether-band core only: also clear cross-chunk Nether-decoration spillover (basalt etc.) from
        // the tunnel cross-section. The band is an overworld-dimension construct, so this no-ops when
        // the corridor runs through another dimension.
        if (level.dimension().equals(Level.OVERWORLD)) {
            cleanNetherClutter(chunk, level, chunkMinX, chunkMinZ, chunkMaxZ, g, cycle, bandStartX);
        }
    }

    /**
     * Inside the overworld Nether band core, remove Nether terrain/decoration clutter
     * ({@link #isNetherClutter}) that spilled across chunk boundaries into the tunnel's airspace, and
     * <b>repair the track surface</b> where it landed on the bed/rail row. Z is {@code airMinZ..airMaxZ}
     * (strictly inside the stone-brick walls); Y is {@code bedY..ceilingY-1} — the tunnel interior PLUS
     * the bed row, because cross-chunk spillover can overwrite the bed (or a rail) after {@code track_bed}
     * laid it and no other pass touches that row. Clutter is cleared via a section-local, no-relight
     * write ({@link #sweepSection}); if any sat on the bed/rail row ({@link #isTrackRowCell}) the authored
     * template is re-stamped over just those columns ({@link TrackGenerator#restampTrackColumns}, never
     * the fallback palette). The band-core column mask is computed ONCE from the pre-resolved
     * {@code cycle} (no per-column config lookups), with whole-chunk early-outs when the band is off /
     * the world is trainless and when no column is in-band.
     */
    private static void cleanNetherClutter(
        LevelChunk chunk, ServerLevel overworld, int chunkMinX, int chunkMinZ, int chunkMaxZ,
        TrackGeometry g, WorldGenCycle cycle, long bandStartX
    ) {
        if (bandStartX == NetherBand.OFF) return;                        // band off / trainless world
        TunnelGeometry tg = TunnelGeometry.from(g);
        int zLo = Math.max(tg.airMinZ(), chunkMinZ);
        int zHi = Math.min(tg.airMaxZ(), chunkMaxZ);
        if (zLo > zHi) return;                                           // corridor not in this chunk's Z span
        int minY = Math.max(overworld.getMinBuildHeight(), g.bedY());    // includes the bed/rail row
        int maxY = Math.min(overworld.getMaxBuildHeight() - 1, tg.ceilingY() - 1);
        if (maxY < minY) return;

        // Band-core column mask, computed ONCE from the hoisted cycle (no per-column fromConfig). Same
        // predicate as NetherBand.isInNetherBiome(ServerLevel,int); the band-off gate above already
        // stands in for that overload's startX != OFF check.
        boolean[] mask = new boolean[16];
        boolean any = false;
        for (int dx = 0; dx < 16; dx++) {
            mask[dx] = NetherBand.isInNetherBiome(cycle, chunkMinX + dx);
            if (mask[dx]) any = true;
        }
        if (!any) return;                                               // no Nether-core column in this chunk

        Set<Long> buried = new HashSet<>();
        sweepSection(chunk, overworld, chunkMinX, zLo, zHi, minY, maxY, mask, CLUTTER, g, buried);

        // Re-stamp the AUTHORED track template (bed + rails + per-block variant sidecar — never the
        // hardcoded fallback) over only the cleared track-row cells, restoring exactly what worldgen laid.
        if (!buried.isEmpty()) {
            TrackGenerator.restampTrackColumns(overworld, chunk, g, buried);
        }
    }

    /**
     * Section-local strip of {@code strip}-matching blocks from the corridor cross-section of one loaded
     * chunk. Skips all-air sections ({@link LevelChunkSection#hasOnlyAir}), reads/writes section voxels
     * directly, and clears matches to air with {@code setBlockState(..., false)} — the Sable-safe,
     * no-relight, no-shape-cascade path used by {@code NetherTransitionEvents} /
     * {@code WorldDisintegrationEvents} (a world-level {@code setBlock} per cell, with its lighting +
     * client packet, was the measured multi-second cost). Each cleared cell is reported to clients via
     * {@link net.minecraft.server.level.ServerChunkCache#blockChanged} (cheap + batched; a no-op for a
     * chunk not yet sent), and the chunk is marked unsaved. {@code colMask} (nullable = all columns)
     * gates the 16 X columns; when {@code buriedOut} is non-null, every cleared cell on the track row
     * ({@link #isTrackRowCell}) is recorded there (packed {@code (x << 32) | (z & 0xFFFFFFFF)}) for a
     * targeted re-stamp. Lighting is intentionally left to the chunk's normal send-time relight
     * (clutter removal in an enclosed tunnel barely changes light).
     */
    private static void sweepSection(
        LevelChunk chunk, ServerLevel level, int chunkMinX,
        int zLo, int zHi, int minY, int maxY,
        boolean[] colMask, Predicate<BlockState> strip, TrackGeometry g, Set<Long> buriedOut
    ) {
        boolean changed = false;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int sectionCount = chunk.getSectionsCount();
        for (int sIdx = 0; sIdx < sectionCount; sIdx++) {
            LevelChunkSection section = chunk.getSection(sIdx);
            if (section.hasOnlyAir()) continue;
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sIdx));
            int yStart = Math.max(minY, baseY);
            int yEnd = Math.min(maxY, baseY + 15);
            if (yStart > yEnd) continue;
            for (int x = chunkMinX; x <= chunkMinX + 15; x++) {
                if (colMask != null && !colMask[x - chunkMinX]) continue;
                int lx = x & 15;
                for (int z = zLo; z <= zHi; z++) {
                    int lz = z & 15;
                    for (int y = yStart; y <= yEnd; y++) {
                        BlockState state = section.getBlockState(lx, y - baseY, lz);
                        if (state.isAir() || !strip.test(state)) continue;
                        if (buriedOut != null && isTrackRowCell(y, z, g)) {
                            buriedOut.add(((long) x << 32) | (z & 0xFFFFFFFFL));
                        }
                        cursor.set(x, y, z);
                        if (state.hasBlockEntity()) chunk.removeBlockEntity(cursor);
                        section.setBlockState(lx, y - baseY, lz, AIR, false);
                        level.getChunkSource().blockChanged(cursor);
                        changed = true;
                    }
                }
            }
        }
        if (changed) chunk.setUnsaved(true);
    }

    /**
     * True for a cell on the track's bed row ({@code bedY}) or rail row ({@code railY}) within the track
     * Z-footprint ({@code trackZMin..trackZMax}) — the cells the track template authors. Basalt here is
     * cleared to air like any clutter, but it also flags the chunk so the authored template is re-stamped
     * afterwards (never the fallback palette) to put the solid bed/rails back. Pure function — unit-tested.
     */
    static boolean isTrackRowCell(int y, int z, TrackGeometry g) {
        return (y == g.bedY() || y == g.railY()) && z >= g.trackZMin() && z <= g.trackZMax();
    }

    /**
     * Nether terrain / decoration that the real-Nether feature pass spills into the tunnel —
     * bulk rock (netherrack / basalt / blackstone / smooth basalt / gilded blackstone / magma),
     * nylium + wart blocks, and the crimson/warped/soul-sand-valley decoration (stems, hyphae,
     * roots, fungi, sprouts, vines, fire). Direct {@code Blocks.X} checks (basalt first — the
     * common offender) so the set is unit-testable; the trailing tag checks are a defensive
     * fallback that also catches modded Nether blocks. <b>Fluids are deliberately excluded</b> —
     * removing lava/water at runtime cascades neighbour fluid updates (the documented hang);
     * worldgen-placed water is drained at generation time by {@code NetherTransitionFeature}.
     */
    static boolean isNetherClutter(BlockState state) {
        if (state.is(Blocks.NETHERRACK)) return true;
        if (state.is(Blocks.BASALT)) return true;
        if (state.is(Blocks.SMOOTH_BASALT)) return true;
        if (state.is(Blocks.BLACKSTONE)) return true;
        if (state.is(Blocks.GILDED_BLACKSTONE)) return true;
        if (state.is(Blocks.MAGMA_BLOCK)) return true;
        if (state.is(Blocks.GLOWSTONE)) return true;
        if (state.is(Blocks.SHROOMLIGHT)) return true;
        if (state.is(Blocks.BONE_BLOCK)) return true;
        if (state.is(Blocks.SOUL_SAND)) return true;
        if (state.is(Blocks.SOUL_SOIL)) return true;
        if (state.is(Blocks.CRIMSON_NYLIUM) || state.is(Blocks.WARPED_NYLIUM)) return true;
        if (state.is(Blocks.NETHER_WART_BLOCK) || state.is(Blocks.WARPED_WART_BLOCK)) return true;
        if (state.is(Blocks.CRIMSON_STEM) || state.is(Blocks.WARPED_STEM)) return true;
        if (state.is(Blocks.CRIMSON_HYPHAE) || state.is(Blocks.WARPED_HYPHAE)) return true;
        if (state.is(Blocks.CRIMSON_ROOTS) || state.is(Blocks.WARPED_ROOTS)) return true;
        if (state.is(Blocks.CRIMSON_FUNGUS) || state.is(Blocks.WARPED_FUNGUS)) return true;
        if (state.is(Blocks.NETHER_SPROUTS)) return true;
        if (state.is(Blocks.WEEPING_VINES) || state.is(Blocks.WEEPING_VINES_PLANT)) return true;
        if (state.is(Blocks.TWISTING_VINES) || state.is(Blocks.TWISTING_VINES_PLANT)) return true;
        if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) return true;
        // Falling blocks (Nether ore_gravel deltas) — settled cross-chunk spillover resting in the
        // lane; ones that actually fall onto the rails are stopped by NetherBandBehaviourEvents.
        if (state.is(Blocks.GRAVEL) || state.is(Blocks.SAND) || state.is(Blocks.RED_SAND)) return true;
        // Defensive tag fallbacks (also catch modded Nether terrain/decoration).
        if (state.is(BlockTags.BASE_STONE_NETHER)) return true;        // netherrack, basalt, blackstone
        if (state.is(BlockTags.NYLIUM)) return true;                   // crimson/warped nylium
        if (state.is(BlockTags.WART_BLOCKS)) return true;              // nether/warped wart blocks
        return false;
    }

    /**
     * Narrow foliage predicate — leaves, vines, saplings, flowers. NOT
     * water / lava / fire / snow / bubble columns / any other replaceable
     * block. Catching fluids here would cascade neighbour fluid updates
     * and re-trigger chunk loads, the exact failure mode of the previous
     * synchronous attempt.
     */
    private static boolean isFoliage(BlockState state) {
        if (state.is(BlockTags.LEAVES)) return true;
        if (state.is(Blocks.VINE)) return true;
        if (state.is(BlockTags.SAPLINGS)) return true;
        if (state.is(BlockTags.SMALL_FLOWERS)) return true;
        if (state.is(BlockTags.TALL_FLOWERS)) return true;
        return false;
    }
}
