package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.editor.VariantOverlayRenderer;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.sable.PhysicsSubstepTuner;
import games.brennan.dungeontrain.track.TrackGenerator;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
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
 * <p>Block clearance is handled at world-gen time
 * ({@code TrackGenerator.placeTracksForChunk}) — by the time a chunk loads
 * its corridor envelope is already air, so the train never has to carve
 * through terrain at runtime.</p>
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

    private static int tickCounter = 0;

    private TrainTickEvents() {}

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            VariantOverlayRenderer.forget(sp);
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

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
            tickCounter++;
            return;
        }

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

        // Displace external fluid from inside each carriage's footprint. The
        // FlowingFluidExternalWaterMixin veto blocks fluid from *flowing into*
        // a carriage AABB, but it cannot remove fluid already in a cell — and
        // a moving train sweeps forward over world cells that may hold water
        // (a track corridor flooded from an adjacent ocean/river, or a water
        // crossing). Clearing those cells each tick keeps the train dry, like
        // a hull pushing water aside; the veto then holds the cell against
        // re-inflow. Together: no fluid into/through the train, water flows
        // around it (see TrainFluidBarrier / plans/snazzy-churning-snowglobe.md).
        for (List<Trains.Carriage> train : trainsById.values()) {
            displaceFluidInFootprint(level, train);
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

        // Tunnel runtime drain removed — tunnels are now generated entirely
        // at worldgen time via TunnelGenerator.placeTunnelStampsAtWorldgen.
        long tAfterTunnels = System.nanoTime();

        long totalMs = (tAfterTunnels - t0) / 1_000_000;
        if (totalMs >= STUCK_TIMING_THRESHOLD_MS) {
            int totalCarriages = 0;
            for (List<Trains.Carriage> train : trainsById.values()) totalCarriages += train.size();
            int nearCarriages = countNearCarriages(level, trainsById);
            JITTER_LOGGER.debug(
                "[stuck.timing] tick={} total={}ms appender={}ms overlay={}ms find={}ms kill={}ms fluid={}ms tracks={}ms tunnels={}ms trains={} carriages={} near={}",
                level.getGameTime(), totalMs,
                (tAfterAppender - t0) / 1_000_000,
                (tAfterOverlay - tAfterAppender) / 1_000_000,
                (tAfterFindTrains - tAfterOverlay) / 1_000_000,
                (tAfterKill - tAfterFindTrains) / 1_000_000,
                (tAfterFluid - tAfterKill) / 1_000_000,
                (tAfterTracks - tAfterFluid) / 1_000_000,
                (tAfterTunnels - tAfterTracks) / 1_000_000,
                trainsById.size(), totalCarriages, nearCarriages);
        }
        tickCounter++;
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
     * Clear external world fluid from inside every resident carriage's
     * footprint, so a moving train stays dry instead of dragging the water it
     * sweeps over (see {@code TrainTickEvents#onLevelTick} call site and
     * {@link games.brennan.dungeontrain.ship.TrainFluidBarrier}).
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
    private static void displaceFluidInFootprint(ServerLevel level, List<Trains.Carriage> train) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Trains.Carriage carriage : train) {
            ManagedShip ship = carriage.ship();
            if (!ship.isResident()) continue;

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
                        if (state.getFluidState().isEmpty()) continue;
                        clearFluid(level, cursor, state);
                    }
                }
            }
        }
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
                && !train_aabb.contains(e.getX(), e.getY(), e.getZ())
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
