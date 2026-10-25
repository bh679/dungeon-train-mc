package games.brennan.dungeontrain.portal;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The world position of a portal carriage group's shell corner — what every corridor frame is
 * measured from.
 *
 * <p><b>Why not the bounding box.</b> The frame used to be the group's live Sable box min corner.
 * A block placed against a ship joins its sub-level, so a player bridging along the outside of a
 * dimensional carriage grew the box a block towards themselves. The whole corridor frame slid a
 * block outward with it, the corridor test then counted the bridger as standing inside, and the
 * facing rule carried them into the room through a solid wall. A block placed under the carriage
 * did the same in Y.</p>
 *
 * <p>The group's {@link TrainTransformProvider#getShipyardOrigin() shipyard origin} is the same
 * corner in plot space, fixed at assembly. Taken through the ship's pose it is where the clean box
 * corner would be, and nothing a player builds onto the train can move it.</p>
 *
 * <p>A grown box can only ever sit at or below the shell corner, never above it, so a shell corner
 * that lands noticeably <i>below</i> the box means the assumption broke for this group (a driver
 * that does not describe it). Then the box is used, as before, and the group is named in the log.</p>
 */
public final class PortalGroupOrigin {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * How far the shell corner may sit below the box corner before it is distrusted. The box lags
     * the pose by a fraction of a block (see {@code PortalPuppets.poseAligned}); a whole block or
     * more is a different shell, not lag.
     */
    static final double MAX_BELOW_BOX = 0.75;

    /** Groups whose first reading has been logged, so each says so once. */
    private static final Set<Long> LOGGED = ConcurrentHashMap.newKeySet();

    private PortalGroupOrigin() {}

    /** The shell corner of {@code ship}, falling back to {@code box}'s min corner. */
    public static Vector3d of(ManagedShip ship, AABBdc box) {
        Vector3d boxMin = new Vector3d(box.minX(), box.minY(), box.minZ());
        BlockPos plot = ship.getKinematicDriver() instanceof TrainTransformProvider provider
            ? provider.getShipyardOrigin() : null;
        if (plot == null) return boxMin;

        Vector3d shell = ship.shipToWorld(new Vector3d(plot.getX(), plot.getY(), plot.getZ()));
        boolean trusted = trusted(shell, boxMin);
        if (LOGGED.add(ship.id())) {
            LOGGER.info("[DungeonTrain] Portal group origin for ship {}: shell ({}, {}, {}) box ({}, {}, {}){}",
                ship.id(), fmt(shell.x), fmt(shell.y), fmt(shell.z),
                fmt(boxMin.x), fmt(boxMin.y), fmt(boxMin.z),
                trusted ? "" : " — shell corner is below the box, using the box");
        }
        return trusted ? shell : boxMin;
    }

    /** Whether a shell corner is consistent with a box that contains the shell. */
    static boolean trusted(Vector3d shell, Vector3d boxMin) {
        return shell.x >= boxMin.x - MAX_BELOW_BOX
            && shell.y >= boxMin.y - MAX_BELOW_BOX
            && shell.z >= boxMin.z - MAX_BELOW_BOX;
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
