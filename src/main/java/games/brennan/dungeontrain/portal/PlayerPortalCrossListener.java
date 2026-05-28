package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
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

            // Player lands at the partner's same world coords. With matching
            // world coords on both sides (v1 design), the player materialises
            // exactly where they crossed but in the target dim. Their
            // currentX > portalX baseline is preserved as their target-dim
            // baseline (via the LAST_X clear in onPlayerChangedDim), so they
            // won't re-transit on their first target-dim tick.
            DimensionTransition transition = new DimensionTransition(
                targetLevel, pos, sp.getDeltaMovement(),
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
