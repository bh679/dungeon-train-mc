package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.worldgen.MixBand;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.SpheresProgressionConfig;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.Disintegration;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.EndBandJobQueue;
import games.brennan.dungeontrain.worldgen.EndBandSampler;
import games.brennan.dungeontrain.worldgen.EndBandSpill;
import games.brennan.dungeontrain.worldgen.EndBandStyle;
import games.brennan.dungeontrain.worldgen.GenProfiler;
import games.brennan.dungeontrain.worldgen.SunlitChunks;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Writes the BetterEnd End-band passes ({@link EndBandStyle}) in: a chunk in one of those passes
 * generates empty of islands ({@code DisintegrationFeature} skips its stamp there), and the real End
 * terrain {@link EndBandSampler} generated off-thread is written into it here.
 *
 * <ul>
 *   <li><b>Queue</b> — a new band chunk is flagged {@link ModDataAttachments#END_BAND_PENDING} and its
 *       sample requested; a chunk that reloads still flagged asks again.</li>
 *   <li><b>Prefetch</b> — every {@link #PREFETCH_INTERVAL_TICKS} ticks, the strip of band chunks just past
 *       each player's view distance (in the train's +X direction) is requested before it exists. Finished
 *       samples for chunks that aren't loaded yet wait in a small {@link #STASH}, and are written the
 *       moment their chunk generates — so at speed the terrain is there on arrival, not popped in.</li>
 *   <li><b>Apply</b> — raw section writes (the Sable-safe path the other bands use), only into cells that
 *       are <b>still air</b> (nothing a player built is overwritten), clear of the track and the train's
 *       airspace ({@link SphereCarveGeometry}), and thinned across the band's fade edges
 *       ({@link EndBandStyle#keepSampledBlock}). Block entities arrive with their NBT. The chunk is then
 *       re-lit and resent ({@link SunlitChunks}).</li>
 *   <li><b>Spill</b> ({@code endBandFeatureSpill}) — a sample also carries what its features wrote past
 *       its edges ({@link EndBandSpill}). Each neighbour's share is written the moment that neighbour is
 *       loaded with its own terrain in, else held in {@link #SPILL_WAITING} until it is (written right
 *       after its own sample, or on its next load). Adds pass the same gate as the sample; a carve only
 *       clears the exact ground block it dug through. Not persisted: spill for a chunk that never comes
 *       back before a restart is simply lost, like the prefetch stash.</li>
 * </ul>
 *
 * <p>Only loaded chunks are ever written ({@code getChunkNow}); nothing here loads or generates a chunk,
 * which keeps it clear of the Sable worldgen deadlock.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WorldEndBandEvents {

    private static final int PREFETCH_INTERVAL_TICKS = 10;
    /** How many chunk columns past the view distance the prefetch strip reaches. */
    private static final int PREFETCH_DEPTH_CHUNKS = 3;
    /** Finished samples held for chunks that haven't generated yet (~120 KB each). */
    private static final int STASH_CAP = 192;
    /** Chunk-X ahead of a player, measured past its view distance, the prefetch strip starts at. */
    private static final int PREFETCH_LEAD_CHUNKS = 1;
    /** Slack past the prefetch strip before a waiting sample job counts as abandoned and is dropped. */
    private static final int DROP_SLACK_CHUNKS = 2;
    /**
     * Finished samples for chunks within a player's view written per tick. Above the general budget
     * ({@code spheresForeignApplyPerTick}) so terrain a player can see never trails behind as void squares,
     * but capped so a burst can't stall the tick.
     */
    private static final int NEAR_APPLY_PER_TICK = 16;

    /** Finished samples waiting to be written, carried across ticks when the budget runs out. Server thread. */
    private static final List<EndBandSampler.Result> FINISHED = new ArrayList<>();

    private static final Set<Heightmap.Types> FULL_HEIGHTMAPS = EnumSet.of(
            Heightmap.Types.WORLD_SURFACE,
            Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.OCEAN_FLOOR);

    /** Samples finished before their chunk existed, oldest evicted first. Server thread only. */
    private static final Map<Long, EndBandSampler.Result> STASH = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, EndBandSampler.Result> eldest) {
            return size() > STASH_CAP;
        }
    };

    /** Stashed samples whose chunk has now loaded — written at the start of the next tick. */
    private static final Map<Long, EndBandSampler.Result> DUE = new LinkedHashMap<>();

    /** Neighbour chunks a held spill may wait for before the oldest is dropped. */
    private static final int SPILL_WAITING_CAP = 512;

    /** Feature spill for band chunks that don't have their own terrain yet (or aren't loaded), by chunk key. */
    private static final Map<Long, List<EndBandSpill>> SPILL_WAITING = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, List<EndBandSpill>> eldest) {
            return size() > SPILL_WAITING_CAP;
        }
    };

    /** Held spill whose (already-terrained) chunk has just reloaded — written at the start of the next tick. */
    private static final Map<Long, List<EndBandSpill>> DUE_SPILL = new LinkedHashMap<>();

    private static int tickCounter;

    private WorldEndBandEvents() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        ChunkAccess chunk = event.getChunk();
        ChunkPos pos = chunk.getPos();
        long pass;
        if (event.isNewChunk()) {
            pass = betterEndPass(level, pos);
            if (pass < 0L) return;
            chunk.setData(ModDataAttachments.END_BAND_PENDING, Boolean.TRUE);
            chunk.setUnsaved(true);
        } else {
            if (!chunk.getData(ModDataAttachments.END_BAND_PENDING)) {
                // Terrain already in; only spill that arrived while it was away may be owed.
                List<EndBandSpill> owed = SPILL_WAITING.remove(pos.toLong());
                if (owed != null) DUE_SPILL.put(pos.toLong(), owed);
                return;
            }
            // A chunk that unloaded before its terrain arrived: ask again.
            pass = betterEndPass(level, pos);
            if (pass < 0L) return;
        }
        EndBandSampler.Result early = STASH.remove(pos.toLong());
        if (early != null) {
            // Prefetched: written on the next tick (never mid-load — a block-entity write here would
            // look the chunk up while it is still being added).
            DUE.put(pos.toLong(), early);
        } else {
            EndBandSampler.request(level, pos, pass, SphereCarveGeometry.of(level).bedY());
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel level = event.getServer().overworld();
        if (level == null) return;
        if (++tickCounter % PREFETCH_INTERVAL_TICKS == 0) prefetch(level);
        if (!DUE.isEmpty()) {
            // Prefetched terrain for chunks that arrived last tick: all of it, so none is seen bare.
            long t0 = GenProfiler.t0();
            for (EndBandSampler.Result r : DUE.values()) deliver(level, r);
            DUE.clear();
            GenProfiler.add(GenProfiler.Bucket.END_BAND_APPLY, t0);
        }
        if (!DUE_SPILL.isEmpty()) {
            long t0 = GenProfiler.t0();
            DUE_SPILL.forEach((key, spills) -> {
                LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
                if (chunk != null) writeSpills(level, chunk, spills);
            });
            DUE_SPILL.clear();
            GenProfiler.add(GenProfiler.Bucket.END_BAND_APPLY, t0);
        }
        EndBandJobQueue.Players players = snapshotPlayers(level);
        int view = level.getServer().getPlayerList().getViewDistance();
        EndBandSampler.updatePlayers(players,
                view + PREFETCH_LEAD_CHUNKS + PREFETCH_DEPTH_CHUNKS + DROP_SLACK_CHUNKS);
        EndBandSampler.drainReady(FINISHED);
        if (!FINISHED.isEmpty()) writeFinished(level, players, view);
    }

    /**
     * Write finished samples nearest player first: up to {@link #NEAR_APPLY_PER_TICK} for chunks within a
     * player's view, and otherwise the general budget. The rest wait for the next tick.
     */
    private static void writeFinished(ServerLevel level, EndBandJobQueue.Players players, int view) {
        long t0 = GenProfiler.t0();
        if (!players.isEmpty()) {
            FINISHED.sort(Comparator.comparingInt(r -> players.distance(r.pos().x, r.pos().z)));
        }
        int farBudget = SpheresProgressionConfig.applyPerTick();
        int written = 0;
        Iterator<EndBandSampler.Result> it = FINISHED.iterator();
        while (it.hasNext()) {
            EndBandSampler.Result r = it.next();
            boolean near = !players.isEmpty() && players.distance(r.pos().x, r.pos().z) <= view;
            if (written >= (near ? NEAR_APPLY_PER_TICK : farBudget)) break;
            it.remove();
            deliver(level, r);
            EndBandSampler.done(r.pos());
            written++;
        }
        GenProfiler.add(GenProfiler.Bucket.END_BAND_APPLY, t0);
    }

    /** The overworld players' chunk positions, for ordering the sampler's queue and the writes. */
    private static EndBandJobQueue.Players snapshotPlayers(ServerLevel level) {
        List<ServerPlayer> list = level.players();
        if (list.isEmpty()) return EndBandJobQueue.Players.NONE;
        int[] xs = new int[list.size()];
        int[] zs = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            ChunkPos at = list.get(i).chunkPosition();
            xs[i] = at.x;
            zs[i] = at.z;
        }
        return new EndBandJobQueue.Players(xs, zs);
    }

    /** Write {@code r} if its chunk is loaded and still owed; stash it if the chunk isn't there yet. */
    private static void deliver(ServerLevel level, EndBandSampler.Result r) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(r.pos().x, r.pos().z);
        if (chunk == null) {
            STASH.put(r.pos().toLong(), r);                // not generated yet (prefetch) or unloaded
        } else if (chunk.getData(ModDataAttachments.END_BAND_PENDING)) {
            apply(level, chunk, r);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        EndBandSampler.clear();
        games.brennan.dungeontrain.worldgen.BopEnd.clear();
        STASH.clear();
        DUE.clear();
        FINISHED.clear();
        SPILL_WAITING.clear();
        DUE_SPILL.clear();
        tickCounter = 0;
    }

    /**
     * The sampled (BetterEnd / BoP) End pass that owns any band column of {@code pos}, else {@code -1}.
     * Columns follow {@link WorldGenCycle#endSourcePassAt}: one pass per chunk everywhere except across the
     * seam of a joined End band, where vanilla and sampled columns interleave — there the sampled side's
     * pass is the one to fetch, and {@link #fill} writes only the columns it owns.
     */
    private static long betterEndPass(ServerLevel level, ChunkPos pos) {
        if (DisintegrationBand.startX(level) == DisintegrationBand.OFF) return -1L;
        WorldGenCycle cycle = MixBand.cycleAt(level, pos.x, pos.z);   // mix zone: the chunk's picked band
        int minX = pos.getMinBlockX();
        if (cycle.endIslandRamp(minX) <= 0.0 && cycle.endIslandRamp(minX + 15) <= 0.0) return -1L;
        long seed = DungeonTrainWorldData.get(level).getGenerationSeed();
        for (int dx = 0; dx < 16; dx++) {
            if (cycle.endIslandRamp(minX + dx) <= 0.0) continue;
            for (int dz = 0; dz < 16; dz++) {
                long pass = cycle.endSourcePassAt(minX + dx, pos.getMinBlockZ() + dz, seed);
                if (EndBandSampler.appliesTo(level.getServer(), cycle.endStyleOfPass(pass))) return pass;
            }
        }
        return -1L;
    }

    /** Request the not-yet-generated band chunks just beyond each player's view, ahead in +X. */
    private static void prefetch(ServerLevel level) {
        int view = level.getServer().getPlayerList().getViewDistance();
        int bedY = -1;
        for (ServerPlayer player : level.players()) {
            ChunkPos at = player.chunkPosition();
            for (int ahead = 0; ahead < PREFETCH_DEPTH_CHUNKS; ahead++) {
                int cx = at.x + view + PREFETCH_LEAD_CHUNKS + ahead;
                for (int cz = at.z - view; cz <= at.z + view; cz++) {
                    ChunkPos pos = new ChunkPos(cx, cz);
                    if (STASH.containsKey(pos.toLong())) continue;
                    if (level.getChunkSource().getChunkNow(cx, cz) != null) continue;
                    long pass = betterEndPass(level, pos);
                    if (pass < 0L) {
                        if (WorldGenCycle.fromConfig().mixPicksAt(cx << 4)) continue;   // mix zone: picks vary by Z
                        break;                             // whole strip column shares X: none of it is band
                    }
                    if (bedY == -1) bedY = SphereCarveGeometry.of(level).bedY();
                    EndBandSampler.request(level, pos, pass, bedY);
                }
            }
        }
    }

    /**
     * Write one finished sample into its chunk and clear the chunk's pending flag; then any spill the
     * neighbours left waiting for this chunk, and this sample's own spill out to its neighbours.
     */
    private static void apply(ServerLevel level, LevelChunk chunk, EndBandSampler.Result r) {
        boolean changed = fill(level, chunk, r);
        chunk.setData(ModDataAttachments.END_BAND_PENDING, Boolean.FALSE);
        chunk.setUnsaved(true);
        List<EndBandSpill> owed = SPILL_WAITING.remove(chunk.getPos().toLong());
        if (owed != null) {
            for (EndBandSpill spill : owed) changed |= writeSpill(level, chunk, spill);
        }
        if (changed) relight(level, chunk);
        r.spill().forEach((key, spill) -> deliverSpill(level, key, spill));
    }

    private static void relight(ServerLevel level, LevelChunk chunk) {
        Heightmap.primeHeightmaps(chunk, FULL_HEIGHTMAPS);
        SunlitChunks.sunlight(level, chunk);               // raw writes skipped the light engine; resends
    }

    /**
     * Hand a neighbour its spill: written now if that chunk is loaded with its own terrain already in,
     * held otherwise ({@link #SPILL_WAITING}) — a chunk that isn't a sampled band chunk gets nothing.
     */
    private static void deliverSpill(ServerLevel level, long key, EndBandSpill spill) {
        ChunkPos pos = new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key));
        if (betterEndPass(level, pos) < 0L) return;
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk != null && !chunk.getData(ModDataAttachments.END_BAND_PENDING)) {
            if (writeSpill(level, chunk, spill)) relight(level, chunk);
            return;
        }
        SPILL_WAITING.computeIfAbsent(key, k -> new ArrayList<>()).add(spill);
    }

    private static void writeSpills(ServerLevel level, LevelChunk chunk, List<EndBandSpill> spills) {
        boolean changed = false;
        for (EndBandSpill spill : spills) changed |= writeSpill(level, chunk, spill);
        if (changed) relight(level, chunk);
    }

    private static boolean fill(ServerLevel level, LevelChunk chunk, EndBandSampler.Result r) {
        ChunkPos pos = r.pos();
        SphereCarveGeometry geo = SphereCarveGeometry.of(level);
        WorldGenCycle cycle = MixBand.cycleAt(level, pos.x, pos.z);    // mix zone: the chunk's picked band
        long seed = DungeonTrainWorldData.get(level).getGenerationSeed();
        int yStart = Math.max(r.minY(), chunk.getMinBuildHeight());
        int yEnd = Math.min(r.minY() + r.height(), chunk.getMaxBuildHeight());
        boolean changed = false;
        for (int dx = 0; dx < 16; dx++) {
            int worldX = pos.getMinBlockX() + dx;
            double ramp = cycle.endIslandRamp(worldX);
            if (ramp <= 0.0) continue;
            for (int dz = 0; dz < 16; dz++) {
                int worldZ = pos.getMinBlockZ() + dz;
                if (!sampledOwns(level, cycle, seed, worldX, worldZ)) continue;
                boolean laneZ = geo.laneZ(worldZ), airZ = geo.airZ(worldZ);
                for (int y = yStart; y < yEnd; y++) {
                    BlockState ns = r.stateAt(dx, y, dz);
                    if (ns.isAir()) continue;
                    changed |= placeSampled(level, chunk, geo, seed, ramp, laneZ, airZ, dx, y, dz, ns, r.blockEntities());
                }
            }
        }
        return changed;
    }

    /**
     * Write one neighbour's spill into {@code chunk}: adds through the same gate as the chunk's own sample,
     * carves only where the cell still holds the ground block they dug through ({@link EndBandSpill#shouldWrite}).
     */
    private static boolean writeSpill(ServerLevel level, LevelChunk chunk, EndBandSpill spill) {
        ChunkPos pos = chunk.getPos();
        SphereCarveGeometry geo = SphereCarveGeometry.of(level);
        WorldGenCycle cycle = MixBand.cycleAt(level, pos.x, pos.z);
        long seed = DungeonTrainWorldData.get(level).getGenerationSeed();
        int yStart = chunk.getMinBuildHeight(), yEnd = chunk.getMaxBuildHeight();
        long[] at = spill.positions();
        boolean changed = false;
        for (int i = 0; i < at.length; i++) {
            int worldX = BlockPos.getX(at[i]), y = BlockPos.getY(at[i]), worldZ = BlockPos.getZ(at[i]);
            if (y < yStart || y >= yEnd || (worldX >> 4) != pos.x || (worldZ >> 4) != pos.z) continue;
            double ramp = cycle.endIslandRamp(worldX);
            if (ramp <= 0.0 || !sampledOwns(level, cycle, seed, worldX, worldZ)) continue;
            int dx = worldX & 15, dz = worldZ & 15;
            BlockState after = spill.after()[i];
            if (after.isAir()) {
                LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                BlockState current = section.getBlockState(dx, y & 15, dz);
                if (!EndBandSpill.shouldWrite(current, spill.before()[i], after) || current.hasBlockEntity()) continue;
                section.setBlockState(dx, y & 15, dz, after, false);
                changed = true;
            } else {
                changed |= placeSampled(level, chunk, geo, seed, ramp, geo.laneZ(worldZ), geo.airZ(worldZ),
                        dx, y, dz, after, spill.blockEntities());
            }
        }
        return changed;
    }

    /** Whether the sampled look owns column {@code (worldX, worldZ)}: across a joined End band's seam the stamped vanilla look owns some. */
    private static boolean sampledOwns(ServerLevel level, WorldGenCycle cycle, long seed, int worldX, int worldZ) {
        return EndBandSampler.appliesTo(level.getServer(), cycle.endSourceLookAt(worldX, worldZ, seed));
    }

    /**
     * The gate every sampled block passes on its way into the live chunk: clear of the track and the
     * train's airspace, thinned across the band's fade, and only into a cell that is still air (never over
     * a build). True if the block was written.
     */
    private static boolean placeSampled(ServerLevel level, LevelChunk chunk, SphereCarveGeometry geo, long seed,
                                        double ramp, boolean laneZ, boolean airZ, int dx, int y, int dz,
                                        BlockState ns, Map<Long, CompoundTag> blockEntities) {
        if (geo.reserved(y, laneZ, airZ)) return false;
        int worldX = chunk.getPos().getMinBlockX() + dx, worldZ = chunk.getPos().getMinBlockZ() + dz;
        if (!EndBandStyle.keepSampledBlock(ramp, Disintegration.coherentNoise(seed, worldX, y, worldZ))) return false;
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
        if (!section.getBlockState(dx, y & 15, dz).isAir()) return false;   // never over a build
        section.setBlockState(dx, y & 15, dz, ns, false);
        if (ns.hasBlockEntity()) placeBlockEntity(level, new BlockPos(worldX, y, worldZ), ns, blockEntities);
        return true;
    }

    private static void placeBlockEntity(ServerLevel level, BlockPos at, BlockState state, Map<Long, CompoundTag> blockEntities) {
        CompoundTag nbt = blockEntities.get(at.asLong());
        BlockEntity be = nbt != null
                ? BlockEntity.loadStatic(at, state, nbt, level.registryAccess())
                : (state.getBlock() instanceof EntityBlock eb ? eb.newBlockEntity(at, state) : null);
        if (be != null) level.setBlockEntity(be);
    }
}
