package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.editor.VariantOverlayRenderer;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.GenProfiler;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.sable.PhysicsFreezeController;
import games.brennan.dungeontrain.ship.sable.PhysicsSubstepTuner;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageFootprint;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.joml.Vector3dc;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-tick server logic for trains. The train architecture is now N
 * single-carriage Sable sub-levels grouped by {@code trainId}; this class
 * dispatches per-train work (kill-ahead runway clearance, track-fill,
 * tunnel-fill) onto a designated lead or tail carriage rather than running
 * heavy operations once per carriage.
 *
 * <p>Block clearance is primarily pre-emptive: the corridor envelope is carved at world-gen time
 * ({@code TrackGenerator.placeTracksForChunk}) and re-swept when a chunk loads
 * ({@code CorridorCleanupEvents}), so in the normal case the train rides into air it never had to
 * carve. {@link #sweepFootprint} is the runtime backstop for what those two cannot cover — blocks
 * that appear after a chunk was swept (player-built, grown, or outside the swept corridor) — and it
 * breaks them properly, with drops, particles and sound, rather than phasing through them.</p>
 *
 * <p>Entities INSIDE the train's current AABB (e.g. dropped items in carriage
 * interiors, like vase loot) are spared — only the runway in front of the
 * lead carriage is wiped.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class TrainTickEvents {

    private static final Logger JITTER_LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.jitter");
    /** Per-tick budget threshold above which a sub-task breakdown logs at DEBUG. */
    private static final long STUCK_TIMING_THRESHOLD_MS = 5;

    /**
     * Diagnostic radius (blocks) for the {@code near=} field in the
     * {@code [stuck.timing]} line. A carriage is counted "near" if its
     * {@link ManagedShip#worldAABB()} is within this distance of any player —
     * a proxy for the set that genuinely needs a live physics body (the player
     * rides/collides only with nearby carriages). Contrast with
     * {@code carriages=} (every resident carriage, all of which are physics-
     * active in Sable's shared Rapier scene today): {@code carriages − near} is
     * the over-held, needlessly physics-ticked set this profiling exists to
     * size. Not a gameplay knob — it does not change what stays resident.
     */
    private static final double PHYSICS_NEAR_RADIUS = 48.0;
    private static final double PHYSICS_NEAR_RADIUS_SQ = PHYSICS_NEAR_RADIUS * PHYSICS_NEAR_RADIUS;

    /** Period (ticks) for the steady-state {@code [mspt]} sample line. 40t = 2s. */
    private static final int MSPT_LOG_PERIOD_TICKS = 40;

    /**
     * Distance ahead of the lead carriage along velocity that
     * {@link #killEntitiesAhead} sweeps each tick. 8 blocks at 2 m/s ≈ 0.1
     * block/tick gives ~80 ticks (4 s) of advance notice to evict mobs.
     */
    private static final int LOOKAHEAD_BLOCKS = 8;

    /**
     * Max world blocks {@link #sweepFootprint} may destroy per level tick, summed across ALL trains in
     * the level (not per carriage — a 30-carriage train would otherwise multiply the worst case by 30).
     *
     * <p>Steady-state hits are ~0: worldgen and {@code CorridorCleanupEvents} pre-clear the collision
     * envelope, so the sweep finds nothing but air. The cap only bites when a train enters an un-swept
     * region, a player walls the corridor, or a gravity-block column keeps collapsing into the envelope.
     * Cells left unbroken are simply retried next tick, so a wall erodes over a few ticks instead of
     * spiking one.</p>
     */
    private static final int MAX_BLOCK_BREAKS_PER_TICK = 256;

    /**
     * Blocks by which {@link #killEntitiesAhead} inflates the spared train AABB for {@link ItemEntity}
     * only, so drops produced by {@link #sweepFootprint} at the train's leading face aren't discarded
     * as runway clutter before they fall behind the train.
     */
    private static final double ITEM_SPARE_INFLATE = 1.5;

    /**
     * Entity-type namespaces whose entities ride the train and must survive
     * the kill-ahead runway sweep. PlayerMobs ({@code playermob:*}) fall off
     * carriages by design and run recovery AI to bridge back on (PlayerMob
     * issue #35, behavior #2); discarding them on contact deletes them before
     * recovery can run. String-keyed so DT needs no compile/runtime dependency
     * on the PlayerMob mod — if PlayerMob isn't installed, no
     * {@code playermob:*} entity types exist and {@link #isTrainPassenger} is
     * simply never true.
     */
    private static final Set<String> TRAIN_PASSENGER_NAMESPACES = Set.of("playermob");

    /** Phase + period for the periodic track-fill drain. */
    private static final int TRACK_FILL_PERIOD_TICKS = 10;
    private static final int TRACK_FILL_PHASE_OFFSET = 5;

    /**
     * Per-tick nanoTime budget for the deferred upside-down-mirror drain (the streaming scenery, not the
     * train's own path — that goes through the un-budgeted backstop). Bounds the added main-thread cost so
     * a generation burst is spread across ticks instead of spiking the gen tick (~7 ms/chunk).
     */
    private static final long MIRROR_DRAIN_BUDGET_NANOS = 3_000_000L; // 3 ms
    /**
     * Blocks ahead of the train (along velocity) the mirror BACKSTOP force-applies, bypassing the budget,
     * so the flipped corridor is never missing under a carriage. Covers the appender's ~48-block forward
     * spawn reach so a just-spawned front carriage lands on already-mirrored terrain.
     */
    private static final int MIRROR_BACKSTOP_AHEAD_BLOCKS = 48;

    private static int tickCounter = 0;

    private TrainTickEvents() {}

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            VariantOverlayRenderer.forget(sp);
        }
    }

    /**
     * Count each newly-<em>generated</em> overworld chunk reaching FULL, for the {@code [gen.timing]}
     * per-chunk normalisation (see {@link GenProfiler}). {@code isNewChunk()} distinguishes fresh
     * generation from a region-file reload; overworld-only matches the band/train dimension. Fires on
     * the main server thread, so a plain counter increment is safe. No-op unless the profiler is enabled.
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!GenProfiler.enabled()) return;
        if (!(event.getLevel() instanceof ServerLevel lvl)) return;
        if (!lvl.dimension().equals(Level.OVERWORLD)) return;
        if (!event.isNewChunk()) return;
        GenProfiler.countChunk();
    }

    /**
     * Drain and log the {@code [gen.timing]} gen-attribution window (see {@link GenProfiler}). Fires
     * from the overworld level tick every {@link #MSPT_LOG_PERIOD_TICKS} ticks, gated on
     * {@code chunksFulled>0} so idle / stationary windows (no new chunks generated → nothing to
     * attribute) stay quiet. The window ms are a <b>parallel sum across worldgen worker threads</b>, so
     * they can exceed the wall-clock window and must not be compared to {@code avgTickMs} — the
     * load-bearing figures are the {@code perChunk} ones. {@code core} is a sub-portion of {@code nether}.
     */
    private static void logGenTiming(ServerLevel level) {
        GenProfiler.Sample s = GenProfiler.sampleAndReset();
        if (s.chunks() <= 0) return;
        JITTER_LOGGER.debug(
            "[gen.timing] dim={} chunksFulled={} dtGenMs={} perChunkDtMs={} | totals df={} nether={} core={} biome={} mirror={} track={} disint={} erosion={} | perChunk df={} nether={} core={} biome={} mirror={} track={} disint={} erosion={}",
            level.dimension().location(), s.chunks(),
            String.format("%.2f", s.dtTotalMs()), String.format("%.3f", s.dtTotalPerChunkMs()),
            String.format("%.2f", s.ms(GenProfiler.Bucket.DF)),
            String.format("%.2f", s.ms(GenProfiler.Bucket.NETHER_FEATURE)),
            String.format("%.2f", s.ms(GenProfiler.Bucket.CORE_REPLACE)),
            String.format("%.2f", s.ms(GenProfiler.Bucket.BIOME_FORCE)),
            String.format("%.2f", s.ms(GenProfiler.Bucket.MIRROR_PRECOMPUTE)),
            String.format("%.2f", s.ms(GenProfiler.Bucket.TRACK_FEATURE)),
            String.format("%.2f", s.ms(GenProfiler.Bucket.DISINTEGRATION)),
            String.format("%.2f", s.ms(GenProfiler.Bucket.EROSION)),
            String.format("%.3f", s.perChunkMs(GenProfiler.Bucket.DF)),
            String.format("%.3f", s.perChunkMs(GenProfiler.Bucket.NETHER_FEATURE)),
            String.format("%.3f", s.perChunkMs(GenProfiler.Bucket.CORE_REPLACE)),
            String.format("%.3f", s.perChunkMs(GenProfiler.Bucket.BIOME_FORCE)),
            String.format("%.3f", s.perChunkMs(GenProfiler.Bucket.MIRROR_PRECOMPUTE)),
            String.format("%.3f", s.perChunkMs(GenProfiler.Bucket.TRACK_FEATURE)),
            String.format("%.3f", s.perChunkMs(GenProfiler.Bucket.DISINTEGRATION)),
            String.format("%.3f", s.perChunkMs(GenProfiler.Bucket.EROSION)));
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        // [gen.timing] flush — drain the worker-thread gen-attribution buckets once per window from the
        // OVERWORLD level only (the buckets are global; flushing per-level would double-reset them). Placed
        // before the no-trains early return below so it still flushes if the train is momentarily culled.
        if (GenProfiler.enabled()
            && level.dimension().equals(Level.OVERWORLD)
            && level.getGameTime() % MSPT_LOG_PERIOD_TICKS == 0) {
            logGenTiming(level);
        }

        long t0 = System.nanoTime();

        // Append-only carriage spawner — adds carriages ahead of (or behind)
        // the player as they move, never erases. Each new carriage is its
        // own Sable sub-level. See plans/wild-leaping-taco.md (Gate B.1).
        TrainCarriageAppender.onLevelTick(level);
        long tAfterAppender = System.nanoTime();

        VariantOverlayRenderer.onLevelTick(level);
        long tAfterOverlay = System.nanoTime();

        Map<UUID, List<Trains.Carriage>> trainsById = Trains.byTrainId(level);
        long tAfterFindTrains = System.nanoTime();
        if (trainsById.isEmpty()) {
            // No trains here — if we had lowered this level's physics substeps for a
            // long train that has since despawned, restore its baseline (#642).
            PhysicsSubstepTuner.restoreIfTuned(level);
            // Still drain any deferred upside-down mirrors: a player can explore the band on foot with the
            // train culled elsewhere, and the marker/queue outlive the train. Backstop is a no-op with no
            // train; the budgeted drain orders by nearest player instead.
            drainPendingMirrors(level, trainsById);
            tickCounter++;
            return;
        }

        // Physics-freeze reconcile (#646 soft-freeze): park (freeze) every carriage no client is
        // tracking — leaving its body in the scene but skipping Sable's per-body work and DT's
        // teleport — so physics cost scales with watched carriages, not train length. Runs every tick
        // so it reacts promptly to players/mobs crossing the freeze boundary. Stacks with the substep
        // tuner above. See PhysicsFreezeController.
        PhysicsFreezeController.reconcile(level, trainsById);

        // Steady-state MSPT sample every MSPT_LOG_PERIOD_TICKS. Unlike the
        // [stuck.timing] line below (which measures only THIS handler's train work
        // and only fires on >5ms ticks), this pairs the server's mean tick time —
        // which INCLUDES the Sable sub-level physics that runs in ServerLevel.tick,
        // the ~70% hotspot invisible to this handler — with the resident (carriages)
        // vs near counts. Lets MSPT-vs-resident-carriage scaling be read straight
        // from the log, no /spark or /tick query. getAverageTickTimeNanos() is
        // server-wide (dominated by the train dimension's physics). See
        // project_sable_physics_shared_scene_resident_scaling.
        if (level.getGameTime() % MSPT_LOG_PERIOD_TICKS == 0) {
            int carriages = 0;
            for (List<Trains.Carriage> t : trainsById.values()) carriages += t.size();

            // Adaptive physics-substep tuning keys off the same resident sub-level
            // count sampled here (see PhysicsSubstepTuner / issue #642). Runs every
            // period regardless of log level — the count is computed unconditionally.
            PhysicsSubstepTuner.reconcile(level, carriages);

            double avgTickMs = level.getServer().getAverageTickTimeNanos() / 1_000_000.0;
            JITTER_LOGGER.debug("[mspt] dim={} avgTickMs={} carriages={} near={} trains={}",
                level.dimension().location(), String.format("%.2f", avgTickMs), carriages,
                countNearCarriages(level, trainsById), trainsById.size());
        }

        // Kill-ahead runs once per train, against the lead carriage's
        // runway. Other carriages of the train are inside the train's
        // AABB, which is what the kill-ahead filter spares.
        for (List<Trains.Carriage> train : trainsById.values()) {
            killEntitiesAhead(level, train);
        }
        long tAfterKill = System.nanoTime();

        // One pass over each carriage's footprint doing both block-breaking and fluid displacement.
        //
        // BREAK: the train is kinematic, so it phases through anything the pre-emptive corridor clears
        // (worldgen + CorridorCleanupEvents) missed — a player's wall, a grown tree, a structure
        // outside the swept corridor. Breaking here is what makes contact visible: particles, sound
        // and drops. Runs AFTER killEntitiesAhead so this tick's drops aren't seen by the kill sweep;
        // by the next tick the train has advanced and they sit safely behind it.
        //
        // FLUID: the FlowingFluidExternalWaterMixin veto blocks fluid from *flowing into* a carriage
        // AABB, but it cannot remove fluid already in a cell — and a moving train sweeps forward over
        // world cells that may hold water (a track corridor flooded from an adjacent ocean/river, or a
        // water crossing). Clearing those cells each tick keeps the train dry, like a hull pushing
        // water aside; the veto then holds the cell against re-inflow. Together: no fluid into/through
        // the train, water flows around it (see TrainFluidBarrier / plans/snazzy-churning-snowglobe.md).
        //
        // The break budget is shared across every train in the level, so one long train (or several)
        // can't multiply the worst-case cost.
        int blocksBroken = 0;
        for (List<Trains.Carriage> train : trainsById.values()) {
            blocksBroken += sweepFootprint(level, train, MAX_BLOCK_BREAKS_PER_TICK - blocksBroken);
        }
        long tAfterFluid = System.nanoTime();

        // ShipyardShifter intentionally NOT invoked on the per-carriage
        // architecture: it would shift each carriage's pivot independently
        // and cause inter-carriage drift. Sable has no 128-chunk shipyard
        // wall, so the shifter's purpose (the VS workaround) is moot.
        // TrainChainManager also stays disabled — chains were a VS-wall
        // workaround. See plans/wild-leaping-taco.md.

        // Track-fill drains the tail's pendingChunks queue. The tail
        // (lowest pIdx) rarely changes once the train is moving forward
        // — only when the appender extends the train backward — so the
        // queue stays put across most of the train's lifetime.
        if (DungeonTrainConfig.getGenerateTracks()
            && Math.floorMod(tickCounter, TRACK_FILL_PERIOD_TICKS) == TRACK_FILL_PHASE_OFFSET) {
            for (List<Trains.Carriage> train : trainsById.values()) {
                Trains.Carriage tail = Trains.tail(train);
                if (tail != null) {
                    TrackGenerator.fillRenderDistance(level, tail.ship(), tail.provider());
                }
            }
        }
        long tAfterTracks = System.nanoTime();

        // Deferred upside-down-mirror drain: a train-fall BACKSTOP (force-apply the chunks under/ahead of
        // the train, bypassing the budget, so the flipped corridor is never missing) + a nanoTime-budgeted
        // nearest-first drain of streaming scenery, so the ~7 ms/chunk mirror is spread across ticks
        // instead of spiking the generation tick. See WorldUpsideDownEvents / project_upside_down_perf.
        drainPendingMirrors(level, trainsById);   // self-logs [ud-drain]; timed below for [stuck.timing]
        long tAfterMirror = System.nanoTime();

        // Tunnel runtime drain removed — tunnels are now generated entirely
        // at worldgen time via TunnelGenerator.placeTunnelStampsAtWorldgen.
        long tAfterTunnels = System.nanoTime();

        long totalMs = (tAfterTunnels - t0) / 1_000_000;
        if (totalMs >= STUCK_TIMING_THRESHOLD_MS) {
            int totalCarriages = 0;
            for (List<Trains.Carriage> train : trainsById.values()) totalCarriages += train.size();
            int nearCarriages = countNearCarriages(level, trainsById);
            JITTER_LOGGER.debug(
                "[stuck.timing] tick={} total={}ms appender={}ms overlay={}ms find={}ms kill={}ms sweep={}ms tracks={}ms mirror={}ms tunnels={}ms trains={} carriages={} near={} broke={}",
                level.getGameTime(), totalMs,
                (tAfterAppender - t0) / 1_000_000,
                (tAfterOverlay - tAfterAppender) / 1_000_000,
                (tAfterFindTrains - tAfterOverlay) / 1_000_000,
                (tAfterKill - tAfterFindTrains) / 1_000_000,
                (tAfterFluid - tAfterKill) / 1_000_000,
                (tAfterTracks - tAfterFluid) / 1_000_000,
                (tAfterMirror - tAfterTracks) / 1_000_000,
                (tAfterTunnels - tAfterMirror) / 1_000_000,
                trainsById.size(), totalCarriages, nearCarriages, blocksBroken);
        }
        tickCounter++;
    }

    /**
     * Deferred upside-down-mirror maintenance, run once per level tick while trains are present:
     * <ol>
     *   <li><b>Backstop</b> — force-apply (no budget) every marked chunk overlapping each train's footprint
     *       plus a forward slab, so the train never rides onto an un-mirrored (corridor-less) chunk.</li>
     *   <li><b>Budgeted drain</b> — apply the remaining marked chunks nearest-first under
     *       {@link #MIRROR_DRAIN_BUDGET_NANOS}, so streaming scenery is mirrored smoothly.</li>
     * </ol>
     * Each applied chunk clears its {@code NEEDS_UPSIDE_DOWN_MIRROR} marker and is resent to trackers.
     * Returns the number of chunks applied this tick (for the timing line).
     */
    private static int drainPendingMirrors(ServerLevel level, Map<UUID, List<Trains.Carriage>> trainsById) {
        long t0 = System.nanoTime();
        java.util.Set<Long> pending = DungeonTrainWorldData.get(level).pendingMirrorChunks();
        int applied = 0;

        // (1) Backstop: checks the marker directly (not queue membership) so it is robust even right after
        // a server restart, before the chunk's reload re-enqueue has fired.
        for (List<Trains.Carriage> train : trainsById.values()) {
            applied += forceApplyMirrorAroundTrain(level, train, pending);
        }
        if (pending.isEmpty()) { logDrain(level, applied, pending, t0); return applied; }

        // (2) Budgeted drain, nearest-first by the first train's footprint centre (typically one train;
        // the backstop already covers every train's own path, so ordering only affects scenery smoothness).
        double refX = 0, refZ = 0;
        boolean haveRef = false;
        for (List<Trains.Carriage> train : trainsById.values()) {
            if (train.isEmpty()) continue;
            AABB a = CarriageFootprint.activeWorldAABB(train);
            refX = (a.minX + a.maxX) * 0.5;
            refZ = (a.minZ + a.maxZ) * 0.5;
            haveRef = true;
            break;
        }
        if (!haveRef) {                                       // no resident train → order by nearest player
            List<? extends Player> players = level.players();
            if (!players.isEmpty()) {
                Player p = players.get(0);
                refX = p.getX();
                refZ = p.getZ();
                haveRef = true;
            }
        }
        Long[] keys = pending.toArray(new Long[0]);
        if (haveRef) {
            final double fx = refX, fz = refZ;
            java.util.Arrays.sort(keys, java.util.Comparator.comparingDouble(k -> {
                double dx = (ChunkPos.getX(k) * 16 + 8) - fx;
                double dz = (ChunkPos.getZ(k) * 16 + 8) - fz;
                return dx * dx + dz * dz;
            }));
        }
        var cache = level.getChunkSource();
        long deadline = System.nanoTime() + MIRROR_DRAIN_BUDGET_NANOS;
        for (Long key : keys) {
            if (System.nanoTime() >= deadline) break;
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            LevelChunk chunk = cache.getChunkNow(cx, cz);
            if (chunk == null) { pending.remove(key); continue; }                 // unloaded → reload re-enqueues
            if (!chunk.hasData(ModDataAttachments.NEEDS_UPSIDE_DOWN_MIRROR.get())) { pending.remove(key); continue; } // already applied
            if (!WorldUpsideDownEvents.neighboursFull(level, cx, cz)) continue;   // palette-race guard → retry next tick
            WorldUpsideDownEvents.applyMirrorAndResend(level, chunk);
            pending.remove(key);
            applied++;
        }
        logDrain(level, applied, pending, t0);
        return applied;
    }

    /** Periodic DEBUG line for the deferred-mirror drain (fires from either call site — train-present or not). */
    private static void logDrain(ServerLevel level, int applied, java.util.Set<Long> pending, long t0) {
        if (applied > 0 && level.getGameTime() % 20 == 0) {
            JITTER_LOGGER.debug("[ud-drain] mirrored={} backlog={} ms={}",
                applied, pending.size(), String.format("%.2f", (System.nanoTime() - t0) / 1_000_000.0));
        }
    }

    /**
     * Force-apply every marked, neighbour-FULL chunk overlapping this train's footprint plus a
     * {@link #MIRROR_BACKSTOP_AHEAD_BLOCKS} forward slab (along velocity) — bypassing the drain budget.
     * The mirror lays the train's corridor floor, so an un-applied chunk under/ahead of the train would
     * drop it into the void; this closes that window. Bounded to the few chunks actually under/ahead of
     * the train, never the whole generation burst.
     */
    private static int forceApplyMirrorAroundTrain(ServerLevel level, List<Trains.Carriage> train, java.util.Set<Long> pending) {
        if (train.isEmpty()) return 0;
        AABB aabb = CarriageFootprint.activeWorldAABB(train);
        if (aabb.getXsize() <= 0 || aabb.getYsize() <= 0 || aabb.getZsize() <= 0) return 0;
        Trains.Carriage lead = Trains.lead(train);
        Vector3dc vel = lead != null ? lead.provider().getTargetVelocity() : null;
        double ex = vel != null ? Math.signum(vel.x()) * MIRROR_BACKSTOP_AHEAD_BLOCKS : 0.0;
        double ez = vel != null ? Math.signum(vel.z()) * MIRROR_BACKSTOP_AHEAD_BLOCKS : 0.0;
        int minCX = Math.floorDiv((int) Math.floor(Math.min(aabb.minX, aabb.minX + ex)), 16);
        int maxCX = Math.floorDiv((int) Math.floor(Math.max(aabb.maxX, aabb.maxX + ex)), 16);
        int minCZ = Math.floorDiv((int) Math.floor(Math.min(aabb.minZ, aabb.minZ + ez)), 16);
        int maxCZ = Math.floorDiv((int) Math.floor(Math.max(aabb.maxZ, aabb.maxZ + ez)), 16);
        var cache = level.getChunkSource();
        int applied = 0;
        for (int cz = minCZ; cz <= maxCZ; cz++) {
            for (int cx = minCX; cx <= maxCX; cx++) {
                LevelChunk chunk = cache.getChunkNow(cx, cz);
                if (chunk == null || !chunk.hasData(ModDataAttachments.NEEDS_UPSIDE_DOWN_MIRROR.get())) continue;
                if (!WorldUpsideDownEvents.neighboursFull(level, cx, cz)) continue;
                WorldUpsideDownEvents.applyMirrorAndResend(level, chunk);
                pending.remove(ChunkPos.asLong(cx, cz));
                applied++;
            }
        }
        return applied;
    }

    /**
     * Count carriages within {@link #PHYSICS_NEAR_RADIUS} of any player — the
     * subset that plausibly needs a live physics body. Called only when a
     * {@code [stuck.timing]} line is about to log (slow ticks), so it adds no
     * steady-state cost. Distance is point-to-AABB (zero when the player is
     * inside the box). Skips non-resident (culled) carriages, whose
     * {@link ManagedShip#worldAABB()} is a stale last-known box (see
     * {@code project_stale_ghost_aabb_collision}).
     */
    private static int countNearCarriages(ServerLevel level, Map<UUID, List<Trains.Carriage>> trainsById) {
        List<? extends Player> players = level.players();
        if (players.isEmpty()) return 0;
        int near = 0;
        for (List<Trains.Carriage> train : trainsById.values()) {
            for (Trains.Carriage carriage : train) {
                ManagedShip ship = carriage.ship();
                if (!ship.isResident()) continue;
                AABBdc box = ship.worldAABB();
                for (Player p : players) {
                    double dx = p.getX() - Mth.clamp(p.getX(), box.minX(), box.maxX());
                    double dy = p.getY() - Mth.clamp(p.getY(), box.minY(), box.maxY());
                    double dz = p.getZ() - Mth.clamp(p.getZ(), box.minZ(), box.maxZ());
                    if (dx * dx + dy * dy + dz * dz <= PHYSICS_NEAR_RADIUS_SQ) {
                        near++;
                        break;
                    }
                }
            }
        }
        return near;
    }

    /**
     * Single per-cell pass over every resident carriage's footprint, doing two things with one
     * traversal and one {@link ServerLevel#getBlockState} read per cell:
     *
     * <ol>
     *   <li><b>Break</b> any world block the carriage body is passing through — with drops, break
     *       particles and break sound ({@link #breakBlock}). The train is kinematic (it teleports
     *       rather than colliding), and the corridor is normally pre-cleared at worldgen and re-swept
     *       at chunk load, so this only finds blocks that appeared afterwards: a player-built wall
     *       across the tracks, a grown tree, a structure outside the swept corridor. Without this they
     *       were silently phased through. Gated on {@link DungeonTrainWorldData#getEffectiveBreakBlocksOnContact()}
     *       (read once here, never per cell) and budgeted by {@link #MAX_BLOCK_BREAKS_PER_TICK}.</li>
     *   <li><b>Displace fluid</b> from cells the break pass left alone, so a moving train stays dry
     *       instead of dragging the water it sweeps over (see
     *       {@link games.brennan.dungeontrain.ship.TrainFluidBarrier}).</li>
     * </ol>
     *
     * <p>The two share byte-identical geometry — same AABB, same centre test, same chunk guard — so
     * running them as one pass halves the traversal cost versus a second sweep.</p>
     *
     * <p><b>The train never eats its own rails.</b> Carriage voxels occupy {@code trainY ..
     * trainY+height-1} while the rails sit at {@code railY = trainY-1} and the bed at {@code trainY-2}
     * ({@link games.brennan.dungeontrain.track.TrackGeometry}), and rotation is forced to identity
     * every tick, so the AABB's {@code minY} is exactly {@code trainY} and the centre test already
     * rejects the rail row ({@code trainY-0.5} is below the box). {@code hardFloorY} re-states that
     * invariant as an explicit guard rather than leaving it resting on a float landing precisely on an
     * integer — any future Y-offset, bounding-box pad or derailment rotation would otherwise silently
     * turn "safe" into "the train destroys the track it is riding on".</p>
     *
     * <p>Returns the number of blocks broken, for the {@code [stuck.timing]} {@code broke=} field.</p>
     *
     * <p>Uses each carriage's own {@link ManagedShip#worldAABB()} (not the
     * train union) so the kept-dry region matches the
     * {@code FlowingFluidExternalWaterMixin} veto exactly — gaps <em>between</em>
     * carriages stay water-permeable and surrounding water flows around the
     * train. Culled carriages are skipped: a stale wrapper reports a frozen
     * last-known AABB (see {@code project_stale_ghost_aabb_collision}).</p>
     *
     * <p>Cost is dominated by the {@link ServerLevel#getFluidState} scan
     * (~441 cells/carriage); the {@code setBlock} only fires the first tick a
     * cell is enveloped, because the veto then keeps it air. Clears are
     * neighbour-notifying (not via {@code SilentBlockOps}, which suppresses
     * neighbour updates) so the wake refills with water once a cell exits the
     * AABB behind the train; fluids drop nothing, so drop-suppression is moot.</p>
     */
    private static int sweepFootprint(ServerLevel level, List<Trains.Carriage> train, int breakBudget) {
        boolean breakBlocks = DungeonTrainWorldData.get(level).getEffectiveBreakBlocksOnContact();
        int broke = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Trains.Carriage carriage : train) {
            ManagedShip ship = carriage.ship();
            if (!ship.isResident()) continue;

            int hardFloorY = breakFloorY(carriage.provider().getTrackGeometry());

            AABBdc box = ship.worldAABB();
            int minX = Mth.floor(box.minX());
            int minY = Mth.floor(box.minY());
            int minZ = Mth.floor(box.minZ());
            int maxX = Mth.floor(box.maxX());
            int maxY = Mth.floor(box.maxY());
            int maxZ = Mth.floor(box.maxZ());

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        // Match the veto's predicate: a cell belongs to the
                        // carriage iff its centre is inside the AABB.
                        if (!box.containsPoint(x + 0.5, y + 0.5, z + 0.5)) continue;
                        cursor.set(x, y, z);
                        if (!level.hasChunkAt(cursor)) continue; // never force-load
                        BlockState state = level.getBlockState(cursor);
                        // Fast-out on air: the overwhelming majority of cells in a cleared corridor,
                        // and cheaper than the getFluidState() the fluid pass used to do on each.
                        if (state.isAir()) continue;

                        if (shouldBreak(breakBlocks, y, hardFloorY, broke, breakBudget)) {
                            // Breaking leaves air (or the legacy fluid of a submerged block), so this
                            // cell needs no fluid displacement afterwards.
                            if (breakBlock(level, cursor)) broke++;
                            continue;
                        }
                        if (state.getFluidState().isEmpty()) continue;
                        clearFluid(level, cursor, state);
                    }
                }
            }
        }
        return broke;
    }

    /**
     * Lowest Y {@link #sweepFootprint} may break at, for a carriage riding {@code geometry}: one above
     * the rail row, so the train can never destroy the track it is riding on (nor the bed below it).
     *
     * <p>Null geometry — a carriage whose track geometry has not been assigned yet — returns
     * {@link Integer#MAX_VALUE}, disabling breaking for that carriage entirely. Failing closed is the
     * right default here: guessing a floor risks eating the track, while skipping a tick or two of
     * breaking costs nothing (the cell is retried next tick). Pure function — unit-tested.</p>
     */
    static int breakFloorY(TrackGeometry geometry) {
        return geometry != null ? geometry.railY() + 1 : Integer.MAX_VALUE;
    }

    /**
     * Whether {@link #sweepFootprint} should break the (already known non-air) block at height
     * {@code y}: the feature must be enabled for this world, the cell must sit at or above
     * {@code hardFloorY} ({@link #breakFloorY}), and the level-wide per-tick budget must not be spent.
     * Pure function — unit-tested.
     */
    static boolean shouldBreak(boolean enabled, int y, int hardFloorY, int broke, int budget) {
        return enabled && y >= hardFloorY && broke < budget;
    }

    /**
     * Destroy the world block at {@code pos} the way a player would see it break: break particles and
     * break sound (vanilla's {@code LevelEvent} 2001) plus full drops, including a container's
     * contents. Returns whether a block was actually destroyed.
     *
     * <p>{@link ServerLevel#destroyBlock(BlockPos, boolean)} is deliberately chosen over
     * {@code Block.dropResources} + {@code setBlock}: it is the single call that fires the effect
     * event, captures the block entity for the drop, and replaces the block with
     * {@code fluidState.createLegacyBlock()} so a submerged block leaves water behind rather than a
     * vacuum. It is equally deliberately NOT {@code SilentBlockOps}, whose whole purpose is to
     * suppress the drops and updates this path wants.</p>
     *
     * <p>Note it does not fire {@code BlockEvent.BreakEvent} (that comes from
     * {@code ServerPlayerGameMode}), so the mod's own break listeners are not spuriously triggered by
     * the train.</p>
     */
    private static boolean breakBlock(ServerLevel level, BlockPos pos) {
        return level.destroyBlock(pos.immutable(), true);
    }

    /**
     * Remove the fluid at {@code pos}: a pure liquid block becomes air; a
     * waterlogged solid is de-waterlogged in place (never deleted — e.g. a
     * waterloggable track-bed block must keep its block). Neighbour-notifying
     * so adjacent fluid re-evaluates (wake refill).
     */
    private static void clearFluid(ServerLevel level, BlockPos pos, BlockState state) {
        BlockPos immut = pos.immutable();
        if (state.getBlock() instanceof LiquidBlock) {
            level.setBlock(immut, Blocks.AIR.defaultBlockState(),
                Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS);
        } else if (state.hasProperty(BlockStateProperties.WATERLOGGED)
            && state.getValue(BlockStateProperties.WATERLOGGED)) {
            level.setBlock(immut, state.setValue(BlockStateProperties.WATERLOGGED, Boolean.FALSE),
                Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS);
        }
    }

    /**
     * Wipe non-player entities in the forward look-ahead slab in front of
     * the train — the runway the lead is about to enter. Entities INSIDE
     * the train's current AABB are spared so dropped items in carriage
     * interiors survive.
     *
     * <p>The slab geometry: train's full AABB (union across all carriages)
     * extended by {@link #LOOKAHEAD_BLOCKS} along each axis whose velocity
     * component is non-zero.</p>
     */
    private static void killEntitiesAhead(ServerLevel level, List<Trains.Carriage> train) {
        if (train.isEmpty()) return;
        AABB aabb = CarriageFootprint.activeWorldAABB(train);
        if (aabb.getXsize() <= 0 || aabb.getYsize() <= 0 || aabb.getZsize() <= 0) return;

        // Velocity is shared across all carriages of a train (they advance
        // in lockstep) — read from any carriage, lead is convenient.
        Trains.Carriage lead = Trains.lead(train);
        if (lead == null) return;
        Vector3dc velocity = lead.provider().getTargetVelocity();

        double signX = Math.signum(velocity.x());
        double signY = Math.signum(velocity.y());
        double signZ = Math.signum(velocity.z());
        if (signX == 0 && signY == 0 && signZ == 0) return; // idle train

        double expX = signX * LOOKAHEAD_BLOCKS;
        double expY = signY * LOOKAHEAD_BLOCKS;
        double expZ = signZ * LOOKAHEAD_BLOCKS;
        AABB expanded = new AABB(
            Math.min(aabb.minX, aabb.minX + expX),
            Math.min(aabb.minY, aabb.minY + expY),
            Math.min(aabb.minZ, aabb.minZ + expZ),
            Math.max(aabb.maxX, aabb.maxX + expX),
            Math.max(aabb.maxY, aabb.maxY + expY),
            Math.max(aabb.maxZ, aabb.maxZ + expZ)
        );

        final AABB train_aabb = aabb;
        List<Entity> victims = level.getEntitiesOfClass(
            Entity.class, expanded,
            // Skip players, dead entities, and entities inside the train's
            // interior AABB. Also skip anything tagged as carriage contents:
            // those entities live in shipyard chunks at absolute shipyard
            // coords, so when the kill-ahead AABB is computed in visible-
            // world coords the contents entities fail the
            // {@code train_aabb.contains} sparing check even though they're
            // logically inside the train. The diagnostic tag survives the
            // {@code Entity.RemovalReason} and is the most reliable identity
            // for "this is one of ours; do not kill."
            e -> !(e instanceof Player) && e.isAlive()
                // Items get a wider spare box than everything else. sweepFootprint breaks blocks
                // strictly inside train_aabb, so its drops start inside the sparing region — but a
                // drop from the frontmost cell column pops with a small outward velocity and can
                // cross maxX into this slab before the train catches up. Inflating for items only
                // keeps train-broken drops collectable without sparing mobs on the runway.
                && !(e instanceof ItemEntity
                    ? train_aabb.inflate(ITEM_SPARE_INFLATE).contains(e.getX(), e.getY(), e.getZ())
                    : train_aabb.contains(e.getX(), e.getY(), e.getZ()))
                && !isCarriageContentsEntity(e)
                // Spare train passengers (e.g. PlayerMob): they fall off
                // carriages by design and recover, so kill-ahead must not
                // discard them on contact. See isTrainPassenger.
                && !isTrainPassenger(e)
        );
        for (Entity e : victims) {
            e.discard();
        }
    }

    /**
     * Returns {@code true} if {@code entity}'s tag set contains any
     * {@code dungeontrain_contents_pidx_*} marker (see
     * {@link CarriageContentsPlacer#DT_CONTENTS_TAG_PREFIX}). Cheap: the
     * tag set is usually empty, and our prefix check exits early on a
     * non-match.
     */
    private static boolean isCarriageContentsEntity(Entity entity) {
        for (String tag : entity.getTags()) {
            if (tag.startsWith(CarriageContentsPlacer.DT_CONTENTS_TAG_PREFIX)) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if {@code e}'s entity-type id is in a train-passenger
     * namespace (see {@link #TRAIN_PASSENGER_NAMESPACES}), e.g. PlayerMob. Such
     * entities are spared by {@link #killEntitiesAhead} so their fall-off
     * recovery AI can run instead of being discarded on contact.
     */
    private static boolean isTrainPassenger(Entity e) {
        return isTrainPassengerId(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
    }

    /**
     * Pure, registry-free core of {@link #isTrainPassenger}: {@code true} iff
     * {@code id}'s namespace is a train-passenger namespace. Package-private and
     * {@code static} so it is unit-testable without a live {@link Entity} or a
     * bootstrapped registry.
     */
    static boolean isTrainPassengerId(ResourceLocation id) {
        return id != null && TRAIN_PASSENGER_NAMESPACES.contains(id.getNamespace());
    }
}
