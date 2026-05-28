package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.joml.Vector3dc;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-tick scanner that detects carriages crossing a dimensional-portal
 * plane and dispatches a {@link PortalTransitService.TransitJob} to handle
 * the cross-dim transfer.
 *
 * <h2>Detection model</h2>
 * <p>For each loaded carriage in the level, we track its world-X position
 * from the previous tick in {@link #LAST_X_BY_SHIP}. On each tick we
 * compare the previous and current X against every portal in this dim.
 * A "crossing" is when the carriage's centre passed from one side of the
 * portal X-plane to the other within a single tick. Direction-agnostic in
 * v1 — both +X and -X crossings trigger transit. (Future: gate to "into
 * intake face" only, per the plan's risk #10.)</p>
 *
 * <p>The Y/Z proximity check ensures the carriage actually went THROUGH the
 * frame, not just across the X-plane at some far-away Y. We require the
 * carriage's world-AABB to intersect the portal's frame volume.</p>
 *
 * <h2>Performance</h2>
 * <p>The naive complexity is O(carriages × portals_in_dim) per tick. In v1
 * we accept that — portal counts grow slowly (one per 1000 blocks) and
 * carriage counts are bounded by render distance. The plan flags a spatial
 * index as a Phase 12 polish item if we measure tick spikes in testing.</p>
 *
 * <h2>Per-ship state</h2>
 * <p>{@link #LAST_X_BY_SHIP} is keyed by {@code ship.id()} (the carriage's
 * sub-level UUID's MSB). Entries are cleaned lazily — a carriage that
 * vanishes (Sable unload, delete after transit, server stop) leaves a
 * stale entry, but the entry is harmless (it will never be queried again).
 * A periodic prune is added in {@link #clearState} which is called on
 * server stop, alongside the spawner's reset.</p>
 */
public final class CarriageTransitDetector {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Half-extent of the portal-frame Y/Z volume that counts as "passed through." */
    private static final double FRAME_HALF_HEIGHT_BLOCKS = 3.5; // matches PortalSpawner.FRAME_HALF_HEIGHT + 0.5
    private static final double FRAME_HALF_WIDTH_BLOCKS = 4.5;  // matches PortalSpawner.FRAME_HALF_WIDTH + 0.5

    /** ship.id() → last-tick world X. */
    private static final Map<Long, Double> LAST_X_BY_SHIP = new ConcurrentHashMap<>();

    private CarriageTransitDetector() {}

    /**
     * Per-tick entry point. Called from {@code TrainTickEvents.onLevelTick}
     * after the appender + portal-spawner passes. {@code trainsById} is the
     * same map already built earlier in the tick — no need to re-query.
     */
    public static void tick(ServerLevel level, Map<UUID, List<Trains.Carriage>> trainsById) {
        if (trainsById.isEmpty()) return;

        // Collect portals in this dim ONCE per tick. Anywhere from 0 to N
        // depending on how much track has been generated.
        PortalRegistry registry = PortalRegistry.get(level.getServer());
        ResourceKey<Level> dim = level.dimension();
        List<PortalEndpoint> portalsInDim = collectEndpointsInDim(registry.all(), dim);
        if (portalsInDim.isEmpty()) {
            // Still update LAST_X so a portal that arrives next tick has a
            // valid prev-X reference for crossing detection. Cheap.
            updateLastX(trainsById);
            return;
        }

        // Build the partner index in a single pass so the inner loop is O(1).
        Map<BlockPos, PortalEndpoint> partnerByPos = new HashMap<>();
        for (PortalPair pair : registry.all()) {
            if (pair.a().dim().equals(dim)) {
                partnerByPos.put(pair.a().pos(), pair.b());
            }
            if (pair.b().dim().equals(dim)) {
                partnerByPos.put(pair.b().pos(), pair.a());
            }
        }

        // For each carriage in each train, check crossings.
        for (List<Trains.Carriage> train : trainsById.values()) {
            for (Trains.Carriage carriage : train) {
                detectAndDispatch(level, carriage, portalsInDim, partnerByPos);
            }
        }
    }

    /**
     * Update {@link #LAST_X_BY_SHIP} for every visible carriage without doing
     * any crossing checks. Used when there are no portals in the dim yet —
     * we still want last-tick X populated so the very first portal spawned
     * later has a baseline to compare against.
     */
    private static void updateLastX(Map<UUID, List<Trains.Carriage>> trainsById) {
        for (List<Trains.Carriage> train : trainsById.values()) {
            for (Trains.Carriage c : train) {
                long id = c.ship().id();
                Vector3dc pos = c.ship().currentWorldPosition();
                LAST_X_BY_SHIP.put(id, pos.x());
            }
        }
    }

    /**
     * Check {@code carriage} against every portal in the dim; if it crossed
     * a portal's X-plane this tick AND its world-AABB overlaps the portal's
     * frame volume, dispatch a {@link PortalTransitService.TransitJob}.
     * Updates {@link #LAST_X_BY_SHIP} unconditionally.
     */
    private static void detectAndDispatch(ServerLevel level, Trains.Carriage carriage,
                                          List<PortalEndpoint> portalsInDim,
                                          Map<BlockPos, PortalEndpoint> partnerByPos) {
        long id = carriage.ship().id();
        Vector3dc pos = carriage.ship().currentWorldPosition();
        double currentX = pos.x();
        Double lastX = LAST_X_BY_SHIP.put(id, currentX);
        if (lastX == null) {
            // First tick we've seen this carriage — no prior X to compare against.
            return;
        }

        // Quick reject: no movement on the X axis.
        if (Math.abs(currentX - lastX) < 1e-6) return;

        for (PortalEndpoint portal : portalsInDim) {
            double portalX = portal.pos().getX() + 0.5;  // block centre
            boolean crossedThisTick = (lastX < portalX && currentX >= portalX)
                                   || (lastX > portalX && currentX <= portalX);
            if (!crossedThisTick) continue;

            // Y/Z proximity check — the carriage's world position has to be
            // within the frame's Y/Z box, not just sharing the same X plane.
            double dy = Math.abs(pos.y() - (portal.pos().getY() + 0.5));
            double dz = Math.abs(pos.z() - (portal.pos().getZ() + 0.5));
            if (dy > FRAME_HALF_HEIGHT_BLOCKS || dz > FRAME_HALF_WIDTH_BLOCKS) continue;

            // Crossing confirmed. Dispatch.
            PortalEndpoint partner = partnerByPos.get(portal.pos());
            if (partner == null) {
                // Unpaired or registry corrupt — skip.
                LOGGER.warn("[Portal] Carriage {} crossed unpaired portal at {} in {}",
                    id, portal.pos(), portal.dim().location());
                continue;
            }

            PortalTransitService.TransitJob job = new PortalTransitService.TransitJob(
                level, carriage, portal.pos(), partner.dim(), partner.pos());
            boolean success = PortalTransitService.transit(job);
            if (success) {
                // Remove from our last-X tracking — the carriage no longer
                // exists in this dim. (Transit service will have deleted it.)
                LAST_X_BY_SHIP.remove(id);
                // Don't check other portals for this carriage — it's gone.
                return;
            }
            // If transit failed (e.g. chunks not ready), leave lastX alone
            // and we'll retry on the next tick. The next tick's lastX still
            // shows the carriage past the portal so the crossing condition
            // will re-fire.
        }
    }

    /** Collect every {@link PortalEndpoint} in {@code dim} from the pair list. */
    private static List<PortalEndpoint> collectEndpointsInDim(Collection<PortalPair> pairs, ResourceKey<Level> dim) {
        List<PortalEndpoint> out = new java.util.ArrayList<>();
        for (PortalPair pair : pairs) {
            if (pair.a().dim().equals(dim)) out.add(pair.a());
            if (pair.b().dim().equals(dim)) out.add(pair.b());
        }
        return out;
    }

    /** Drop all per-carriage tracking. Wired to server stop. */
    public static void clearState() {
        LAST_X_BY_SHIP.clear();
    }
}
