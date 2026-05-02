package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.editor.VariantOverlayRenderer;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.train.CarriageFootprint;
import games.brennan.dungeontrain.train.TrainCarriageAppender;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
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
     * Distance ahead of the lead carriage along velocity that
     * {@link #killEntitiesAhead} sweeps each tick. 8 blocks at 2 m/s ≈ 0.1
     * block/tick gives ~80 ticks (4 s) of advance notice to evict mobs.
     */
    private static final int LOOKAHEAD_BLOCKS = 8;
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
            tickCounter++;
            return;
        }

        // Kill-ahead runs once per train, against the lead carriage's
        // runway. Other carriages of the train are inside the train's
        // AABB, which is what the kill-ahead filter spares.
        for (List<Trains.Carriage> train : trainsById.values()) {
            killEntitiesAhead(level, train);
        }
        long tAfterKill = System.nanoTime();

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
            JITTER_LOGGER.debug(
                "[stuck.timing] tick={} total={}ms appender={}ms overlay={}ms find={}ms kill={}ms tracks={}ms tunnels={}ms trains={} carriages={}",
                level.getGameTime(), totalMs,
                (tAfterAppender - t0) / 1_000_000,
                (tAfterOverlay - tAfterAppender) / 1_000_000,
                (tAfterFindTrains - tAfterOverlay) / 1_000_000,
                (tAfterKill - tAfterFindTrains) / 1_000_000,
                (tAfterTracks - tAfterKill) / 1_000_000,
                (tAfterTunnels - tAfterTracks) / 1_000_000,
                trainsById.size(), totalCarriages);
        }
        tickCounter++;
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
            e -> !(e instanceof Player) && e.isAlive()
                && !train_aabb.contains(e.getX(), e.getY(), e.getZ())
        );
        for (Entity e : victims) {
            e.discard();
        }
    }
}
