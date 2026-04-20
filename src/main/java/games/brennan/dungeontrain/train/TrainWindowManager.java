package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
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
            for (int i = pIdx - halfBack; i <= pIdx + halfFront; i++) {
                desired.add(i);
            }
        }

        if (desired.isEmpty()) return;

        Set<Integer> current = provider.getActiveIndices();

        for (Integer i : desired) {
            if (current.contains(i)) continue;
            BlockPos carriageOrigin = new BlockPos(originX + i * CarriageTemplate.LENGTH, originY, originZ);
            CarriageTemplate.placeAt(level, carriageOrigin, CarriageTemplate.typeForIndex(i));
            current.add(i);
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
            LOGGER.debug("[DungeonTrain] Erased carriage idx={} at shipyard {}", i, carriageOrigin);
        }
    }
}
