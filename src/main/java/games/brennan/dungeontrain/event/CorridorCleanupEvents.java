package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.track.TrackPalette;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.world.StartingDimension;
import games.brennan.dungeontrain.worldgen.NetherBand;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

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
 * the stone-brick bed (or a rail) <em>after</em> this chunk's {@code track_bed} already laid it, and
 * no other pass repairs that — every clearance protects the track row by starting at {@code bedY+1}.
 * So on the actual track cells we <b>restore</b> the bed / rails ({@link #trackRepairBlock}) instead
 * of clearing to air; off-track clutter still goes to air. Fluids stay excluded (the cascade hazard
 * above); worldgen-placed water is drained at generation time by {@code NetherTransitionFeature}
 * instead. The stone-brick walls sit outside {@code airMinZ..airMaxZ}, so they're never touched.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
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

    /** ChunkPos longs that have been queued by {@link #onChunkLoad}, awaiting drain. */
    private static final Deque<Long> PENDING = new ConcurrentLinkedDeque<>();

    /** ChunkPos longs already cleaned this session. In-memory only. */
    private static final Set<Long> CLEANED = ConcurrentHashMap.newKeySet();

    private CorridorCleanupEvents() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        MinecraftServer server = level.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        StartingDimension expected = data.startingDimension();
        if (!level.dimension().equals(expected.levelKey())) return;

        ChunkAccess chunk = event.getChunk();
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

        long key = ChunkPos.asLong(cx, cz);
        if (CLEANED.contains(key)) return;
        // offer() onto the tail; drain pops from head so cleanup follows
        // load order ≈ spatial proximity to the player.
        PENDING.offer(key);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
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

        int budget = drainBudget(PENDING.size());
        while (budget > 0) {
            Long key = PENDING.poll();
            if (key == null) break;
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            if (CLEANED.contains(key)) continue;
            if (!level.getChunkSource().hasChunk(cx, cz)) {
                // Chunk unloaded between enqueue and drain. Drop it; if it
                // reloads, ChunkEvent.Load re-enqueues.
                continue;
            }
            cleanCorridorChunk(level, cx, cz, dims, g);
            CLEANED.add(key);
            budget--;
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
     * Whether a chunk's Z span [{@code chunkMinZ}..{@code chunkMaxZ}] overlaps the corridor Z span
     * [{@code corridorZMin}..{@code corridorZMax}]. Used as the {@link #onChunkLoad} enqueue prefilter
     * against the tunnel airspace span, so a chunk holding only the outer corridor pad (when the
     * corridor straddles a chunk Z-boundary) is never dropped. Pure function — unit-tested.
     */
    static boolean chunkOverlapsCorridorZ(int chunkMinZ, int chunkMaxZ, int corridorZMin, int corridorZMax) {
        return chunkMaxZ >= corridorZMin && chunkMinZ <= corridorZMax;
    }

    private static void cleanCorridorChunk(
        ServerLevel level, int cx, int cz, CarriageDims dims, TrackGeometry g
    ) {
        int chunkMinX = cx << 4;
        int chunkMaxX = chunkMinX + 15;
        int chunkMinZ = cz << 4;
        int chunkMaxZ = chunkMinZ + 15;
        int zLo = Math.max(g.trackZMin(), chunkMinZ);
        int zHi = Math.min(g.trackZMax(), chunkMaxZ);

        // Y range covers the bed + rail rows AND the carriage envelope, so
        // cross-chunk foliage spillover into template-authored air gaps in
        // the bed / rail rows also gets cleaned. The predicate is narrow
        // enough that the bed and rail blocks themselves stay intact.
        int trainY = g.bedY() + 2;
        int minY = Math.max(level.getMinBuildHeight(), g.bedY());
        int maxY = Math.min(level.getMaxBuildHeight() - 1, trainY + dims.height());

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        if (maxY >= minY) {
            for (int x = chunkMinX; x <= chunkMaxX; x++) {
                for (int z = zLo; z <= zHi; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        cursor.set(x, y, z);
                        BlockState state = level.getBlockState(cursor);
                        if (state.isAir()) continue;
                        if (!isFoliage(state)) continue;
                        level.setBlock(cursor, AIR, Block.UPDATE_CLIENTS);
                    }
                }
            }
        }

        // Nether-band core only: also clear cross-chunk Nether-decoration spillover
        // (basalt etc.) from the tunnel cross-section. The band is an overworld-dimension
        // construct, so this no-ops when the corridor runs through another dimension.
        if (level.dimension().equals(Level.OVERWORLD)) {
            cleanNetherClutter(level, cursor, chunkMinX, chunkMaxX, chunkMinZ, chunkMaxZ, g);
        }
    }

    /**
     * Inside the overworld Nether band core, remove Nether terrain/decoration clutter
     * ({@link #isNetherClutter}) that spilled across chunk boundaries into the tunnel's airspace, and
     * <b>repair the track surface</b> where it landed on the bed/rail row. Z is {@code airMinZ..airMaxZ}
     * (strictly inside the stone-brick walls); Y is {@code bedY..ceilingY-1} — the tunnel interior PLUS
     * the bed row, because cross-chunk spillover can overwrite the stone-brick bed (or a rail) after
     * {@code track_bed} laid it and no other pass touches that row. Off-track clutter is cleared to air;
     * a buried bed/rail cell is restored via {@link #trackRepairBlock}. Per-column band gate; a cheap
     * whole-chunk early-out skips worlds with the band disabled / no train.
     */
    private static void cleanNetherClutter(
        ServerLevel overworld, BlockPos.MutableBlockPos cursor,
        int chunkMinX, int chunkMaxX, int chunkMinZ, int chunkMaxZ, TrackGeometry g
    ) {
        if (NetherBand.startX(overworld) == NetherBand.OFF) return;     // band off / trainless world
        TunnelGeometry tg = TunnelGeometry.from(g);
        int zLo = Math.max(tg.airMinZ(), chunkMinZ);
        int zHi = Math.min(tg.airMaxZ(), chunkMaxZ);
        if (zLo > zHi) return;                                          // corridor not in this chunk's Z span
        int minY = Math.max(overworld.getMinBuildHeight(), g.bedY());   // includes the bed/rail row
        int maxY = Math.min(overworld.getMaxBuildHeight() - 1, tg.ceilingY() - 1);
        if (maxY < minY) return;

        for (int x = chunkMinX; x <= chunkMaxX; x++) {
            if (!NetherBand.isInNetherBiome(overworld, x)) continue;    // core columns only
            for (int z = zLo; z <= zHi; z++) {
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    BlockState state = overworld.getBlockState(cursor);
                    if (state.isAir()) continue;
                    if (!isNetherClutter(state)) continue;
                    // Restore the bed/rails if the clutter buried a track cell; otherwise clear to air.
                    BlockState repair = trackRepairBlock(y, z, g, tg);
                    overworld.setBlock(cursor, repair != null ? repair : AIR, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /**
     * The track block to restore at a corridor cell that cross-chunk Nether spillover buried, or
     * {@code null} when the cell is not part of the track surface (the caller clears those to air).
     * The track cells {@code track_bed} authors — and that a later neighbouring-chunk basalt feature
     * can overwrite, with no other pass repairing them — are the bed row ({@code bedY} across the
     * track Z-span {@code trackZMin..trackZMax}) and the two rail columns ({@code railY} at
     * {@code railZMin}/{@code railZMax}). Pure function — unit-tested.
     */
    static BlockState trackRepairBlock(int y, int z, TrackGeometry g, TunnelGeometry tg) {
        if (y == g.bedY() && z >= g.trackZMin() && z <= g.trackZMax()) return TrackPalette.BED;
        if (y == g.railY() && (z == tg.railZMin() || z == tg.railZMax())) return TrackPalette.RAIL;
        return null;
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
