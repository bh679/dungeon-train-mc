package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.worldgen.MixBand;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.EndBandConfig;
import games.brennan.dungeontrain.config.SpheresProgressionConfig;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.EndBandJobQueue;
import games.brennan.dungeontrain.worldgen.EndBandSampler;
import games.brennan.dungeontrain.worldgen.EndBandSpill;
import games.brennan.dungeontrain.worldgen.EndBandSpillWaiting;
import games.brennan.dungeontrain.worldgen.EndBandStyle;
import games.brennan.dungeontrain.worldgen.GenProfiler;
import games.brennan.dungeontrain.worldgen.OwnerScopedQueue;
import games.brennan.dungeontrain.worldgen.PendingChunkSweep;
import games.brennan.dungeontrain.worldgen.PrefetchDirection;
import games.brennan.dungeontrain.worldgen.SunlitChunks;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Writes the BetterEnd End-band passes ({@link EndBandStyle}) in: a chunk in one of those passes
 * generates empty of islands ({@code DisintegrationFeature} skips its stamp there), and the real End
 * terrain {@link EndBandSampler} generated off-thread is written into it here.
 *
 * <ul>
 *   <li><b>Queue</b> — a new band chunk is flagged {@link ModDataAttachments#END_BAND_PENDING} and its
 *       sample requested; a chunk that reloads still flagged asks again.</li>
 *   <li><b>Prefetch</b> — every {@link #PREFETCH_INTERVAL_TICKS} ticks, the strip of band chunks just past
 *       each player's view distance, on the side they are heading ({@link PrefetchDirection}: +X with the
 *       train, −X walking the reversed cycle behind spawn), is requested before it exists. Finished
 *       samples for chunks that aren't loaded yet wait in a small {@link #STASH}, and are written the
 *       moment their chunk generates — so at speed the terrain is there on arrival, not popped in.</li>
 *   <li><b>Sweep</b> — on the same beat, every loaded chunk within a player's view that is still pending
 *       is re-checked ({@link PendingChunkSweep}): its stashed sample is written, or its sample asked for
 *       again. A chunk only asks when it loads, so without this one that lost its request while staying
 *       loaded (a job dropped before any player was near, a sample stashed mid-load) stayed void.</li>
 *   <li><b>Apply</b> — raw section writes (the Sable-safe path the other bands use), only into cells that
 *       are <b>still air</b> (nothing a player built is overwritten), clear of the track and the train's
 *       airspace ({@link SphereCarveGeometry}), and thinned across the band's fade edges
 *       ({@link EndBandStyle#keepSampledBlock}). Block entities arrive with their NBT. The chunk is then
 *       re-lit and resent ({@link SunlitChunks}).</li>
 *   <li><b>Spill</b> ({@code endBandFeatureSpill}) — a sample also carries what its features wrote past
 *       its edges ({@link EndBandSpill}). Each neighbour's share is written the moment that neighbour is
 *       loaded with its own terrain in (re-lit once at the end of the tick, however many neighbours wrote),
 *       else held in {@link #SPILL_WAITING} — one per neighbour — until it is (written right after its own
 *       sample, or on its next load). Adds pass the same gate as the sample; a carve only clears the exact
 *       ground block it dug through; and only columns of the spill's own pass take it. Not persisted: spill
 *       for a chunk that never comes back before a restart is simply lost, like the prefetch stash.</li>
 *   <li><b>Stop</b> — all of this state is static, so it is dropped when the server stops, twice: at
 *       stopping, and again at stopped. Between the two the server is still finishing chunk work — a
 *       worldgen worker can hand over spill ({@link #offerSpill}) and a chunk load can ask for a sample —
 *       and none of that may reach the next world opened in the same JVM. Spill handed over from worldgen
 *       also carries its server, and is dropped if drained by another.</li>
 * </ul>
 *
 * <p>Only loaded chunks are ever written ({@code getChunkNow}); nothing here loads or generates a chunk,
 * which keeps it clear of the Sable worldgen deadlock.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WorldEndBandEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

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

    /** Each player's world X at the last prefetch, for {@link PrefetchDirection}. Server thread only. */
    private static final Map<UUID, Double> LAST_PREFETCH_X = new HashMap<>();

    /**
     * Feature spill for band chunks that don't have their own terrain yet (or aren't loaded), by chunk key —
     * at most one per neighbour ({@link EndBandSpillWaiting}).
     */
    private static final EndBandSpillWaiting<EndBandSpill> SPILL_WAITING =
            new EndBandSpillWaiting<>(EndBandSpillWaiting.DEFAULT_CAP);

    /** Held spill whose (already-terrained) chunk has just reloaded — written at the start of the next tick. */
    private static final Map<Long, List<EndBandSpill>> DUE_SPILL = new LinkedHashMap<>();

    /**
     * Spill produced by samples written in worldgen ({@link EndBandInlineTerrain}), handed over from the worker
     * threads with the server it was generated for.
     */
    private static final OwnerScopedQueue<Map<Long, EndBandSpill>> INLINE_SPILL = new OwnerScopedQueue<>();

    /**
     * Already-terrained chunks spill was written into this tick, re-lit and resent once at its end
     * ({@link #relightTouched}) rather than once per neighbour's spill.
     */
    private static final Map<Long, LevelChunk> TOUCHED = new LinkedHashMap<>();

    private static int tickCounter;

    private WorldEndBandEvents() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        ChunkAccess chunk = event.getChunk();
        ChunkPos pos = chunk.getPos();
        EndBandLoadDecision.Action action = EndBandLoadDecision.decide(event.isNewChunk(),
                chunk.getData(ModDataAttachments.END_BAND_PENDING), EndBandConfig.terrainInWorldgen());
        if (action == EndBandLoadDecision.Action.NONE) {
            // Terrain already in (written in worldgen, or before it unloaded); only spill that arrived
            // while it was away may be owed.
            List<EndBandSpill> owed = SPILL_WAITING.take(pos.toLong());
            if (!owed.isEmpty()) DUE_SPILL.put(pos.toLong(), owed);
            return;
        }
        long pass = betterEndPass(level, pos);
        if (pass < 0L) return;
        if (action == EndBandLoadDecision.Action.FLAG_AND_REQUEST) {
            chunk.setData(ModDataAttachments.END_BAND_PENDING, Boolean.TRUE);
            chunk.setUnsaved(true);
        }
        // Else: a chunk that unloaded before its terrain arrived (or whose worldgen write failed): ask again.
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
        if (++tickCounter % PREFETCH_INTERVAL_TICKS == 0) {
            // In worldgen mode a chunk arrives with its terrain, so there is nothing to fetch ahead; the
            // sweep still repairs old saves' pending chunks and any worldgen write that failed.
            if (!EndBandConfig.terrainInWorldgen()) prefetch(level);
            sweepPending(level);
        }
        drainInlineSpill(level);
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
                if (chunk == null) return;
                boolean changed = false;
                for (EndBandSpill spill : spills) changed |= writeSpill(level, chunk, spill);
                if (changed) TOUCHED.put(key, chunk);
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
        if (!TOUCHED.isEmpty()) {
            long t0 = GenProfiler.t0();
            relightTouched(level);
            GenProfiler.add(GenProfiler.Bucket.END_BAND_APPLY, t0);
        }
    }

    /** Re-light and resend each chunk spill was written into this tick, once however many neighbours wrote. */
    private static void relightTouched(ServerLevel level) {
        for (LevelChunk chunk : TOUCHED.values()) relight(level, chunk);
        TOUCHED.clear();
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
        clearState();
    }

    /**
     * Clear again once the server has fully stopped. Stopping fires before {@code stopServer()}, which keeps
     * ticking chunk work until none is left: a worldgen step already running finishes there and hands over
     * its spill, and a chunk load there can queue a sample — both after the first clear. By now no worldgen
     * step can still be running (a chunk can't unload while one holds it).
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        int lateSpill = INLINE_SPILL.size();
        if (lateSpill > 0) {
            LOGGER.debug("[DungeonTrain] Dropped {} End-band spill hand-over(s) made while the server was stopping", lateSpill);
        }
        clearState();
    }

    /** Drop every sample, stash and spill this class (and the End sampler) holds. */
    private static void clearState() {
        EndBandSampler.clear();
        games.brennan.dungeontrain.worldgen.BopEnd.clear();
        STASH.clear();
        DUE.clear();
        FINISHED.clear();
        LAST_PREFETCH_X.clear();
        SPILL_WAITING.clear();
        DUE_SPILL.clear();
        INLINE_SPILL.clear();
        TOUCHED.clear();
        tickCounter = 0;
    }

    /**
     * A worldgen-time sample's spill for its neighbours, generated for {@code server}; delivered on that
     * server's next tick, never another's. Any thread.
     */
    static void offerSpill(MinecraftServer server, Map<Long, EndBandSpill> spill) {
        INLINE_SPILL.offer(server, spill);
    }

    private static void drainInlineSpill(ServerLevel level) {
        if (INLINE_SPILL.size() == 0) return;
        int dropped = INLINE_SPILL.drain(level.getServer(),
                spill -> spill.forEach((key, s) -> deliverSpill(level, key, s)));
        if (dropped > 0) {
            LOGGER.debug("[DungeonTrain] Dropped {} End-band spill hand-over(s) left from a previous server", dropped);
        }
    }

    /**
     * The sampled (BetterEnd / BoP) End pass that owns any band column of {@code pos}, else {@code -1}.
     * Columns follow {@link WorldGenCycle#endSourcePassAt}: one pass per chunk everywhere except across the
     * seam of a joined End band, where vanilla and sampled columns interleave — there the sampled side's
     * pass is the one to fetch, and {@link EndBandTerrainWriter} writes only the columns it owns.
     * Any thread (reads only per-world data and memoised band layout).
     */
    static long betterEndPass(ServerLevel level, ChunkPos pos) {
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

    /** Request the not-yet-generated band chunks just beyond each player's view, on the side they are heading. */
    private static void prefetch(ServerLevel level) {
        long anchorX = DisintegrationBand.startX(level);
        if (anchorX == DisintegrationBand.OFF) {
            LAST_PREFETCH_X.clear();
            return;
        }
        int view = level.getServer().getPlayerList().getViewDistance();
        int bedY = -1;
        Map<UUID, Double> seen = new HashMap<>();
        for (ServerPlayer player : level.players()) {
            double x = player.getX();
            Double last = LAST_PREFETCH_X.get(player.getUUID());
            seen.put(player.getUUID(), x);
            int dir = PrefetchDirection.pick(last == null ? Double.NaN : x - last, x < anchorX);
            ChunkPos at = player.chunkPosition();
            for (int ahead = 0; ahead < PREFETCH_DEPTH_CHUNKS; ahead++) {
                int cx = at.x + dir * (view + PREFETCH_LEAD_CHUNKS + ahead);
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
        LAST_PREFETCH_X.clear();                           // players who left drop out
        LAST_PREFETCH_X.putAll(seen);
    }

    /**
     * Re-check every loaded chunk within a player's view that is still pending ({@link PendingChunkSweep}):
     * write its stashed sample next tick, or ask for it again ({@link EndBandSampler#request} ignores a
     * chunk already in flight). Reads only chunks already loaded ({@code getChunkNow}).
     */
    private static void sweepPending(ServerLevel level) {
        if (DisintegrationBand.startX(level) == DisintegrationBand.OFF) return;
        int view = level.getServer().getPlayerList().getViewDistance();
        int bedY = -1;
        Set<Long> visited = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            ChunkPos at = player.chunkPosition();
            for (int cx = at.x - view; cx <= at.x + view; cx++) {
                for (int cz = at.z - view; cz <= at.z + view; cz++) {
                    long key = ChunkPos.asLong(cx, cz);
                    if (!visited.add(key)) continue;
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) continue;
                    PendingChunkSweep.Action action = PendingChunkSweep.decide(
                            chunk.getData(ModDataAttachments.END_BAND_PENDING),
                            STASH.containsKey(key), DUE.containsKey(key));
                    if (action == PendingChunkSweep.Action.WRITE_STASHED) {
                        DUE.put(key, STASH.remove(key));
                    } else if (action == PendingChunkSweep.Action.REQUEST) {
                        ChunkPos pos = chunk.getPos();
                        long pass = betterEndPass(level, pos);
                        if (pass < 0L) continue;
                        if (bedY == -1) bedY = SphereCarveGeometry.of(level).bedY();
                        EndBandSampler.request(level, pos, pass, bedY);
                    }
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
        for (EndBandSpill spill : SPILL_WAITING.take(chunk.getPos().toLong())) changed |= writeSpill(level, chunk, spill);
        if (changed) {
            relight(level, chunk);
            TOUCHED.remove(chunk.getPos().toLong());   // just re-lit with everything in it
        }
        r.spill().forEach((key, spill) -> deliverSpill(level, key, spill));
    }

    private static void relight(ServerLevel level, LevelChunk chunk) {
        Heightmap.primeHeightmaps(chunk, FULL_HEIGHTMAPS);
        SunlitChunks.sunlight(level, chunk);               // raw writes skipped the light engine; resends
    }

    /**
     * Hand a neighbour its spill: written now if that chunk is loaded with its own terrain already in (and
     * re-lit at the end of the tick, {@link #TOUCHED}), held otherwise ({@link #SPILL_WAITING}) — a chunk that
     * isn't a sampled band chunk gets nothing.
     */
    private static void deliverSpill(ServerLevel level, long key, EndBandSpill spill) {
        ChunkPos pos = new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key));
        if (betterEndPass(level, pos) < 0L) return;
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk != null && !chunk.getData(ModDataAttachments.END_BAND_PENDING)) {
            if (writeSpill(level, chunk, spill)) TOUCHED.put(key, chunk);
            return;
        }
        SPILL_WAITING.hold(key, spill.source(), spill);
    }

    private static boolean fill(ServerLevel level, LevelChunk chunk, EndBandSampler.Result r) {
        return EndBandTerrainWriter.write(level, chunk, r, EndBandTerrainWriter.liveSink(level, chunk));
    }

    /**
     * Write one neighbour's spill into {@code chunk}: adds through the same gate as the chunk's own sample,
     * carves only where the cell still holds the ground block they dug through ({@link EndBandSpill#shouldWrite}),
     * and only in columns the spill's own pass owns ({@link EndBandSpill#ownedBy}) — never across a seam into a
     * vanilla-stamped column or another sampled pass's.
     */
    private static boolean writeSpill(ServerLevel level, LevelChunk chunk, EndBandSpill spill) {
        ChunkPos pos = chunk.getPos();
        SphereCarveGeometry geo = SphereCarveGeometry.of(level);
        WorldGenCycle cycle = MixBand.cycleAt(level, pos.x, pos.z);
        long seed = DungeonTrainWorldData.get(level).getGenerationSeed();
        int yStart = chunk.getMinBuildHeight(), yEnd = chunk.getMaxBuildHeight();
        long[] at = spill.positions();
        EndBandTerrainWriter.Sink sink = EndBandTerrainWriter.liveSink(level, chunk);
        boolean changed = false;
        for (int i = 0; i < at.length; i++) {
            int worldX = BlockPos.getX(at[i]), y = BlockPos.getY(at[i]), worldZ = BlockPos.getZ(at[i]);
            if (y < yStart || y >= yEnd || (worldX >> 4) != pos.x || (worldZ >> 4) != pos.z) continue;
            double ramp = cycle.endIslandRamp(worldX);
            if (ramp <= 0.0 || !spill.ownedBy(cycle.endSourcePassAt(worldX, worldZ, seed))) continue;
            int dx = worldX & 15, dz = worldZ & 15;
            BlockState after = spill.after()[i];
            if (after.isAir()) {
                BlockState current = sink.get(dx, y, dz);
                if (!EndBandSpill.shouldWrite(current, spill.before()[i], after) || current.hasBlockEntity()) continue;
                sink.set(dx, y, dz, after);
                changed = true;
            } else {
                changed |= EndBandTerrainWriter.place(pos, geo, seed, ramp, geo.laneZ(worldZ), geo.airZ(worldZ),
                        dx, y, dz, after, spill.blockEntities(), sink);
            }
        }
        return changed;
    }
}
