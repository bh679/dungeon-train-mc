package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.joml.Vector3dc;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-tick detector for players walking through a dimensional portal volume.
 * Triggers vanilla {@link DimensionTransition} when the player's centre
 * crosses a portal's X-plane while inside the frame's Y/Z bounds.
 *
 * <h2>Why a separate detector from {@link CarriageTransitDetector}?</h2>
 * <p>A player riding a carriage that just transited (Phase 8) is migrated
 * by {@link PortalTransitService} alongside the carriage. But a player
 * who walks through the portal on foot — between carriages, or stepping
 * off the train — needs an independent path. This listener handles the
 * on-foot case.</p>
 *
 * <p>The two paths are non-conflicting:</p>
 * <ul>
 *   <li>If the player is in a carriage's AABB at carriage-transit time,
 *       {@link PortalTransitService#transit} migrates them. This listener
 *       sees them in the target dim AFTER transit; their {@link #LAST_X}
 *       entry was cleared by {@link #onPlayerChangedDim} so the first
 *       target-dim tick just baselines their X without firing transit.</li>
 *   <li>If the player crosses the portal on foot without being in a
 *       carriage AABB, this listener triggers their migration via
 *       {@link ServerPlayer#changeDimension}. The vanilla loading flash
 *       is unavoidable in v1.</li>
 * </ul>
 *
 * <h2>Why an @EventBusSubscriber instead of a per-tick call from TrainTickEvents?</h2>
 * <p>Player ticks fire at the {@code GAME} bus, but the event hook lets us
 * react per-player rather than walking every player from
 * {@code TrainTickEvents}'s level-level handler. Mostly equivalent in
 * performance; the per-player event keeps the responsibility colocated
 * with portal logic and avoids extending TrainTickEvents further.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PlayerPortalCrossListener {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Match the carriage detector's frame proximity values exactly. */
    private static final double FRAME_HALF_HEIGHT_BLOCKS = PortalSpawner.FRAME_HALF_HEIGHT + 0.5;
    private static final double FRAME_HALF_WIDTH_BLOCKS = PortalSpawner.FRAME_HALF_WIDTH + 0.5;

    /**
     * Player UUID → last-tick world X in their current dim. Cleared on
     * dimension change so the new-dim's first tick just baselines without
     * triggering a re-transit.
     */
    private static final java.util.Map<UUID, Double> LAST_X = new ConcurrentHashMap<>();

    private PlayerPortalCrossListener() {}

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!(sp.level() instanceof ServerLevel level)) return;

        UUID id = sp.getUUID();
        Vec3 pos = sp.position();
        double currentX = pos.x;
        Double lastX = LAST_X.put(id, currentX);
        if (lastX == null) return;
        if (Math.abs(currentX - lastX) < 1e-6) return;

        // Look up portals in this dim. Cheap to query the registry per
        // player per tick — the registry's `all()` returns an unmodifiable
        // snapshot, and portal counts grow slowly.
        PortalRegistry registry = PortalRegistry.get(level.getServer());
        ResourceKey<Level> dim = level.dimension();

        for (PortalPair pair : registry.all()) {
            PortalEndpoint endpoint;
            PortalEndpoint partner;
            if (pair.a().dim().equals(dim)) {
                endpoint = pair.a();
                partner = pair.b();
            } else if (pair.b().dim().equals(dim)) {
                endpoint = pair.b();
                partner = pair.a();
            } else {
                continue;
            }

            double portalX = endpoint.pos().getX() + 0.5;
            boolean crossed = (lastX < portalX && currentX >= portalX)
                           || (lastX > portalX && currentX <= portalX);
            if (!crossed) continue;

            double dy = Math.abs(pos.y - (endpoint.pos().getY() + 0.5));
            double dz = Math.abs(pos.z - (endpoint.pos().getZ() + 0.5));
            if (dy > FRAME_HALF_HEIGHT_BLOCKS || dz > FRAME_HALF_WIDTH_BLOCKS) continue;

            // Crossing confirmed. Resolve target level and dispatch.
            ServerLevel targetLevel = level.getServer().getLevel(partner.dim());
            if (targetLevel == null) {
                LOGGER.warn("[Portal] Player crossed portal but target dim {} unresolved",
                    partner.dim().location());
                continue;
            }

            // Compute landing position. The naive "same world coords" choice
            // looks correct because both portals share world coords in v1,
            // but the train in the target dim has been racing ahead of the
            // partner portal since the first carriage transited — by the
            // time a player crosses on foot, the target train is typically
            // ~5–30 blocks past the partner portal in +X. Landing at the
            // static partner portal puts the player into open air (or
            // raw Nether/End terrain after vanilla's safe-snap shoves them
            // down through the empty portal interior) — the "under the
            // train" report.
            //
            // Fix: find the nearest train carriage in the target dim and
            // land the player ON TOP of it at the same X,Z as that
            // carriage. Falls back to a static "well above the partner
            // portal" position if no train is nearby (rare — happens only
            // before the source train has transited any carriages, e.g.
            // when a player teleports themselves to a portal ahead of the
            // train).
            Vec3 landingPos = findLandingPosition(targetLevel, endpoint.pos(), pos);
            DimensionTransition transition = new DimensionTransition(
                targetLevel, landingPos, sp.getDeltaMovement(),
                sp.getYRot(), sp.getXRot(),
                DimensionTransition.DO_NOTHING);
            try {
                sp.changeDimension(transition);
            } catch (Throwable t) {
                LOGGER.error("[Portal] Player dim change threw for {}", sp.getName().getString(), t);
                continue;
            }
            LOGGER.info("[Portal] Player {} crossed {} → {} at {}",
                sp.getName().getString(),
                dim.location(), partner.dim().location(), endpoint.pos());
            return; // don't check other portals this tick
        }
    }

    /**
     * Maximum block distance from the partner portal in which we consider a
     * carriage "nearby enough" to land the player on. Should cover the
     * typical lag between transit and player crossing — at 5 blocks/s and a
     * worst-case 6-second crossing delay, the train can be 30 blocks past
     * the portal. 64 gives generous headroom.
     */
    private static final double LANDING_SEARCH_RADIUS_SQ = 64.0 * 64.0;

    /**
     * Pick where to teleport the player on the target side. Prefers landing
     * the player on top of the nearest carriage to the partner portal so
     * they don't free-fall through empty target-dim terrain. Falls back to
     * partner portal coords with a generous Y offset above the frame if no
     * carriage is within {@link #LANDING_SEARCH_RADIUS_SQ}.
     *
     * <p>{@code sourcePos} is only used to inherit lateral (X,Z) drift the
     * player had inside the portal frame — currently we ignore it and align
     * to the carriage's own X,Z so the player lands exactly on top.</p>
     */
    private static Vec3 findLandingPosition(ServerLevel targetLevel, net.minecraft.core.BlockPos partnerPortalPos, Vec3 sourcePos) {
        double portalCx = partnerPortalPos.getX() + 0.5;
        double portalCy = partnerPortalPos.getY() + 0.5;
        double portalCz = partnerPortalPos.getZ() + 0.5;

        ManagedShip nearest = null;
        double bestDistSq = LANDING_SEARCH_RADIUS_SQ;
        TrainTransformProvider nearestProvider = null;

        for (ManagedShip ship : Shipyards.of(targetLevel).findAll()) {
            if (!(ship.getKinematicDriver() instanceof TrainTransformProvider provider)) continue;
            Vector3dc p = ship.currentWorldPosition();
            double dx = p.x() - portalCx;
            double dy = p.y() - portalCy;
            double dz = p.z() - portalCz;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                nearest = ship;
                nearestProvider = provider;
            }
        }

        if (nearest != null) {
            Vector3dc p = nearest.currentWorldPosition();
            // currentWorldPosition is the carriage AABB centre. Land on top
            // of the carriage: centre Y + half-height + small clearance so
            // the player's feet rest cleanly on the carriage roof.
            double topY = p.y() + nearestProvider.dims().height() / 2.0 + 0.1;
            return new Vec3(p.x(), topY, p.z());
        }

        // Fallback: well above the partner portal frame so the player at
        // least doesn't materialise inside the frame's solid perimeter.
        return new Vec3(portalCx, portalCy + 8.0, portalCz);
    }

    /** Drop the player's last-X tracking on dimension change. */
    @SubscribeEvent
    public static void onPlayerChangedDim(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            LAST_X.remove(sp.getUUID());
        }
    }

    /** Drop the player's last-X tracking on logout. */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            LAST_X.remove(sp.getUUID());
        }
    }

    /** Drop all tracking on server stop. */
    public static void clearState() {
        LAST_X.clear();
    }
}
