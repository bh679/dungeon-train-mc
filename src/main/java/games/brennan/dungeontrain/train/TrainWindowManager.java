package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.bodies.properties.BodyTransform;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-tick rolling-window driver: keeps each {@link TrainTransformProvider}-marked
 * ship's carriage blocks aligned with the union of per-player windows.
 *
 * For each player within {@link #NEAR_RADIUS} blocks of a train:
 *   - Project world position to ship-local via {@code ship.transform.worldToShip}.
 *   - Compute carriage index {@code pIdx = floor((localX - shipyardOriginX) / LENGTH)}.
 *   - Contribute carriage indices {@code [pIdx - halfBack, pIdx + halfFront]}.
 *
 * Active set = union across players. Diff against the provider's recorded indices:
 *   - Missing → place a carriage at that shipyard offset.
 *   - Stale → erase it, unless a player is currently inside (safety).
 */
public final class TrainWindowManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double NEAR_RADIUS = 128.0;
    private static final double NEAR_RADIUS_SQ = NEAR_RADIUS * NEAR_RADIUS;
    // Ticks to wait before accepting a reversed pIdx shift. ~1/3 second at
    // 20 Hz server tick: long enough to absorb single-tick flaps caused by
    // block-change/transform sync races, short enough that real direction
    // changes feel responsive.
    private static final int REVERSAL_COOLDOWN_TICKS = 6;

    private TrainWindowManager() {}

    public static void onLevelTick(ServerLevel level) {
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        for (LoadedServerShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            if (!(ship.getTransformProvider() instanceof TrainTransformProvider provider)) continue;
            updateWindow(level, ship, provider, players);
        }
    }

    private static void updateWindow(
        ServerLevel level,
        LoadedServerShip ship,
        TrainTransformProvider provider,
        List<ServerPlayer> players
    ) {
        int count = provider.getCount();
        int halfBack = (count - 1) / 2;
        int halfFront = count - halfBack - 1;
        BlockPos shipyardOrigin = provider.getShipyardOrigin();
        int originX = shipyardOrigin.getX();
        int originY = shipyardOrigin.getY();
        int originZ = shipyardOrigin.getZ();

        Vector3dc shipWorldPos = ship.getTransform().getPosition();
        double shipWx = shipWorldPos.x();
        double shipWy = shipWorldPos.y();
        double shipWz = shipWorldPos.z();

        Set<Integer> current = provider.getActiveIndices();
        Set<Integer> desired = new HashSet<>();
        Set<Integer> occupied = new HashSet<>();

        for (ServerPlayer player : players) {
            double dx = player.getX() - shipWx;
            double dy = player.getY() - shipWy;
            double dz = player.getZ() - shipWz;
            if (dx * dx + dy * dy + dz * dz > NEAR_RADIUS_SQ) continue;

            Vector3d local = new Vector3d(player.getX(), player.getY(), player.getZ());
            ship.getTransform().getWorldToShip().transformPosition(local);
            int pIdx = (int) Math.floor((local.x - originX) / (double) CarriageTemplate.LENGTH);

            occupied.add(pIdx);

            // Reversal debounce: if the player's pIdx flipped direction in the
            // last few ticks, the previous intended shift is still propagating
            // to clients. Honour the reversal only after REVERSAL_COOLDOWN
            // ticks have passed since the last committed shift. Within the
            // cooldown, treat pIdx as unchanged so the window doesn't flap.
            int committed = provider.getCommittedPIdx();
            int lastDir = provider.getLastShiftDirection();
            long now = level.getGameTime();
            long ticksSince = now - provider.getLastShiftTick();
            int effectivePIdx = pIdx;
            if (pIdx != committed) {
                int dir = Integer.signum(pIdx - committed);
                if (lastDir != 0 && dir == -lastDir && ticksSince < REVERSAL_COOLDOWN_TICKS) {
                    // Reversal too soon — suppress. Player still rides on committed.
                    effectivePIdx = committed;
                } else {
                    provider.setCommittedPIdx(pIdx);
                    provider.setLastShiftDirection(dir);
                    provider.setLastShiftTick(now);
                }
            }

            for (int i = effectivePIdx - halfBack; i <= effectivePIdx + halfFront; i++) {
                desired.add(i);
            }
        }

        if (desired.isEmpty()) return;

        boolean mutated = false;
        for (Integer i : desired) {
            if (current.contains(i)) continue;
            BlockPos carriageOrigin = new BlockPos(originX + i * CarriageTemplate.LENGTH, originY, originZ);
            CarriageTemplate.placeAt(level, carriageOrigin, CarriageTemplate.typeForIndex(i));
            current.add(i);
            mutated = true;
            LOGGER.debug("[DungeonTrain] Added carriage idx={} at shipyard {}", i, carriageOrigin);
        }

        Set<Integer> toErase = new HashSet<>();
        for (Integer i : current) {
            if (desired.contains(i)) continue;
            if (occupied.contains(i)) continue;
            toErase.add(i);
        }
        for (Integer i : toErase) {
            BlockPos carriageOrigin = new BlockPos(originX + i * CarriageTemplate.LENGTH, originY, originZ);
            CarriageTemplate.eraseAt(level, carriageOrigin);
            current.remove(i);
            mutated = true;
            LOGGER.debug("[DungeonTrain] Erased carriage idx={} at shipyard {}", i, carriageOrigin);
        }

        // After voxel mutations, re-push the compensated transform in the
        // same server tick. This tightens the client-visible window between
        // "new voxels applied" and "new transform applied" — otherwise the
        // client can render the new blocks against the stale transform for
        // one frame, producing a brief backwards teleport (most visible when
        // a flatbed carriage enters/leaves the window because the flatbed's
        // small mass swings the COM by more blocks per shift).
        if (mutated) {
            BodyTransform compensated = provider.computeCompensatedTransform(ship.getTransform());
            if (compensated != null) {
                ship.unsafeSetTransform(compensated);
            }
        }
    }
}
