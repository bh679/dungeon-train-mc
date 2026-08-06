package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.portal.PortalCarriageLayout;
import games.brennan.dungeontrain.portal.PortalCarriageSelection;
import games.brennan.dungeontrain.portal.PortalFrames;
import games.brennan.dungeontrain.portal.PortalRegistry;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the hallway portal for corridors that ride the train.
 *
 * <p>Unlike {@link PortalTransitEvents}, which shifts a player by a constant {@code deltaY} between
 * two stationary copies, the carriage copy moves. So the rule here is a mapping between frames
 * (see {@link PortalFrames}): crossing the midpoint outbound drops the player off the moving train
 * into a static twin corridor, and crossing back re-reads the carriage's position <i>at that
 * moment</i>, so they rejoin the train wherever it has since travelled to.</p>
 *
 * <p><b>Per carriage, not per sub-level.</b> One Sable sub-level holds a whole group —
 * {@code [BACK pad | groupSize carriages | FRONT pad]} — so a ship's AABB spans several carriages
 * and its minimum corner is a pad, not a corridor. Each portal carriage's origin is derived from
 * the group's anchor index and {@link CarriagePlacer#halfPadLen}.</p>
 *
 * <p><b>Origins are read as exact doubles, never floored.</b> The carriage frame comes straight from
 * the live ship AABB. That matters twice over: flooring to a block would make the mapping lurch a
 * whole block as the train rolls, and reading the origin live means the train's known positional
 * jitter cancels — the corridor's blocks and its origin move together, so the player's position
 * within the corridor stays correct however much the group is drifting.</p>
 *
 * <p><b>The twin is stamped on approach, not at the crossing.</b> It goes {@link #TWIN_Y_OFFSET}
 * blocks above the carriage's current position — the same chunk columns, so it is already loaded,
 * sent to the client and meshed by the time the swap happens ({@code ViewArea} sizes its render grid
 * to the full build height). Doing it on approach also keeps a few thousand block writes away from
 * the instant the player crosses.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalCarriageEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** All five axes relative: velocity and the render interpolation baseline both survive the move. */
    private static final Set<RelativeMovement> RELATIVE_ALL = EnumSet.allOf(RelativeMovement.class);

    /** How far above the carriage the twin is stamped. Same chunk columns, so always loaded. */
    private static final int TWIN_Y_OFFSET = 96;

    /**
     * Stamp the twin once a player is this close to the portal carriage — near enough to be about to
     * walk in. Deliberately tight: with a portal every few carriages, a generous range would keep
     * several twins alive at once and re-stamp each of them every time the train rolled on, which is
     * thousands of block writes a second for corridors nobody is walking into.
     */
    private static final double APPROACH_RANGE = 12.0;

    /**
     * Re-stamp the twin once the carriage has rolled this far from it. The twin has to stay inside
     * the chunks the client already has, or the swap would land the player in unloaded space — the
     * one thing this whole approach exists to avoid. 24 blocks keeps it within a chunk or two of the
     * carriage even at the smallest render distances.
     */
    private static final double TWIN_MAX_DRIFT = 24.0;

    /** Keep the twin this far below the build ceiling. */
    private static final int CEILING_MARGIN = 4;

    /**
     * Carriage index → world origin of the twin currently stamped for it. Carriage indices are
     * global along the track, so they key this on their own. In-memory only: the twin's blocks are
     * re-stamped on the next approach anyway.
     */
    private static final Map<Integer, BlockPos> TWINS = new HashMap<>();

    private PortalCarriageEvents() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;

        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;
        if (PortalRegistry.get(level).carriageEvery() <= PortalCarriageSelection.CARRIAGE_EVERY_OFF) return;

        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims);
        int groupSize = DungeonTrainConfig.getGroupSize();
        int padLen = CarriagePlacer.halfPadLen(dims);

        for (UUID trainId : Trains.byTrainId(level).keySet()) {
            for (Map.Entry<Integer, ManagedShip> group : Trains.knownGroups(trainId).entrySet()) {
                int anchorPIdx = group.getKey();
                AABBdc bb = group.getValue().worldAABB();
                if (bb == null) continue;

                for (int slot = 0; slot < groupSize; slot++) {
                    int carriageIndex = anchorPIdx + slot;
                    if (!PortalCarriageSelection.isPortalCarriage(level, carriageIndex)) continue;

                    // Group layout: [BACK pad | carriage 0 | carriage 1 | … ]. Exact doubles, so the
                    // mapping neither lurches on a block boundary nor fights the group's jitter.
                    double originX = bb.minX() + padLen + (double) slot * dims.length();
                    double originY = bb.minY();
                    double originZ = bb.minZ();

                    handlePortalCarriage(level, players, layout, dims, carriageIndex,
                        originX, originY, originZ);
                }
            }
        }
    }

    private static void handlePortalCarriage(ServerLevel level, List<ServerPlayer> players,
                                             PortalCarriageLayout layout, CarriageDims dims,
                                             int carriageIndex, double originX, double originY, double originZ) {
        BlockPos existingTwin = TWINS.get(carriageIndex);

        // Anyone standing in either corridor pins the pairing: re-stamping the twin out from under a
        // player would strand them in an abandoned corridor that no longer maps to anything.
        boolean occupied = existingTwin != null
            && anyPlayerInCorridor(players, layout, existingTwin.getX(), existingTwin.getY(), existingTwin.getZ())
            || anyPlayerInCorridor(players, layout, originX, originY, originZ);

        if (!occupied && !anyPlayerWithin(players, originX, originY, originZ, APPROACH_RANGE)) {
            return;
        }

        BlockPos twinOrigin = occupied && existingTwin != null
            ? existingTwin
            : ensureTwin(level, dims, carriageIndex, originX, originY, originZ);
        if (twinOrigin == null) return;

        PortalFrames frames = new PortalFrames(layout,
            new PortalFrames.Origin(originX, originY, originZ),
            new PortalFrames.Origin(twinOrigin.getX(), twinOrigin.getY(), twinOrigin.getZ()));

        for (ServerPlayer player : players) {
            if (player.isPassenger()) continue;

            double px = player.getX(), py = player.getY(), pz = player.getZ();
            PortalFrames.Move move = frames.requiredMove(px, py, pz);
            if (move == null) continue;

            player.connection.teleport(move.x(), move.y(), move.z(),
                player.getYRot(), player.getXRot(), RELATIVE_ALL);

            LOGGER.info("[DungeonTrain] Portal carriage swap: player={} carriage={} → {} ({}, {}, {}) → ({}, {}, {})",
                player.getName().getString(), carriageIndex,
                move.toFrame() == PortalFrames.FRAME_TWIN ? "TWIN" : "CARRIAGE",
                fmt(px), fmt(py), fmt(pz), fmt(move.x()), fmt(move.y()), fmt(move.z()));
        }
    }

    /**
     * The twin for this carriage, stamping it if there is none yet or the carriage has rolled out of
     * the chunk columns the old one sits in — which is the condition the crossing's seamlessness
     * depends on, so it is also exactly when a fresh one is worth the block writes.
     */
    private static BlockPos ensureTwin(ServerLevel level, CarriageDims dims, int carriageIndex,
                                       double originX, double originY, double originZ) {
        BlockPos existing = TWINS.get(carriageIndex);
        BlockPos wanted = BlockPos.containing(originX, originY + TWIN_Y_OFFSET, originZ);

        if (wanted.getY() + dims.height() > level.getMaxBuildHeight() - CEILING_MARGIN) return existing;

        if (existing != null && horizontalDistance(existing, originX, originZ) <= TWIN_MAX_DRIFT) {
            return existing;
        }

        // Clear the outgoing twin rather than leaving it hanging in the sky. Without this the train
        // would trail a line of abandoned corridors, one every time it drifted out of range.
        if (existing != null) {
            PortalCarriageBuilder.eraseTwin(level, existing, dims);
        }

        PortalCarriageBuilder.stampTwin(level, wanted, dims);
        TWINS.put(carriageIndex, wanted);
        LOGGER.info("[DungeonTrain] Stamped portal twin for carriage {} at {} (carriage at {}, {}, {})",
            carriageIndex, wanted, fmt(originX), fmt(originY), fmt(originZ));
        return wanted;
    }

    private static double horizontalDistance(BlockPos twin, double x, double z) {
        double dx = twin.getX() - x;
        double dz = twin.getZ() - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** True if any player is inside the corridor whose local origin is at the given world position. */
    private static boolean anyPlayerInCorridor(List<ServerPlayer> players, PortalCarriageLayout layout,
                                               double originX, double originY, double originZ) {
        for (ServerPlayer player : players) {
            if (layout.insideCorridor(player.getX() - originX, player.getY() - originY,
                player.getZ() - originZ)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyPlayerWithin(List<ServerPlayer> players,
                                           double x, double y, double z, double range) {
        double r2 = range * range;
        for (ServerPlayer player : players) {
            if (player.distanceToSqr(x, y, z) <= r2) return true;
        }
        return false;
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
