package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.portal.PortalCarriageLayout;
import games.brennan.dungeontrain.portal.PortalCarriageRole;
import games.brennan.dungeontrain.portal.PortalCarriageSelection;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import games.brennan.dungeontrain.portal.PortalEditMirror;
import games.brennan.dungeontrain.portal.PortalFrames;
import games.brennan.dungeontrain.portal.PortalPairIndex;
import games.brennan.dungeontrain.portal.PortalRegistry;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.sable.SableManagedShip;
import org.joml.Vector3d;
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
     * ENTRY carriage index → world origin of that pair's structure. Carriage indices are global
     * along the track, so the entry index keys a pair on its own. In-memory only: the blocks are
     * re-stamped on the next approach anyway.
     */
    private static final Map<Integer, BlockPos> STRUCTURES = new HashMap<>();

    /**
     * How far outside the corridor's own cross-section the room extends, for the "is anyone in this
     * structure" test. The room is wider and taller than a corridor, and a player standing in it
     * must still pin the structure against being re-stamped.
     */
    private static final int POCKET_ROOM_SLACK = 8;

    /**
     * Ticks after a swap during which that player is left alone.
     *
     * <p>Belt to the hysteresis band's braces, and it covers a different cause: the band absorbs
     * positional disagreement, this absorbs the round trip. A teleport leaves the server ignoring
     * the client's movement until the acknowledgement arrives, so for a tick or two the position it
     * is judging is not one the client has agreed to yet.</p>
     */
    private static final int SWAP_COOLDOWN_TICKS = 6;

    /** Player → game time at which they may swap again. */
    private static final Map<UUID, Long> COOLDOWNS = new HashMap<>();

    private PortalCarriageEvents() {}

    private static boolean onCooldown(ServerPlayer player, long gameTime) {
        Long until = COOLDOWNS.get(player.getUUID());
        if (until == null) return false;
        if (gameTime >= until) {
            COOLDOWNS.remove(player.getUUID());
            return false;
        }
        return true;
    }

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

                    int every = PortalRegistry.get(level).carriageEvery();
                    PortalCarriageRole role = PortalCarriageRole.roleFor(carriageIndex, every);
                    int pairKey = PortalCarriageRole.entryIndexOf(carriageIndex, every);

                    handlePortalCarriage(level, players, layout, dims, carriageIndex, role, pairKey,
                        group.getValue(), originX, originY, originZ);
                }
            }
        }
    }

    private static void handlePortalCarriage(ServerLevel level, List<ServerPlayer> players,
                                             PortalCarriageLayout layout, CarriageDims dims,
                                             int carriageIndex, PortalCarriageRole role, int pairKey,
                                             ManagedShip ship,
                                             double originX, double originY, double originZ) {
        // One structure per pair, stamped from the ENTRY carriage's approach and keyed on its index,
        // so both carriages of a pair address the same room rather than building one each.
        BlockPos structure = STRUCTURES.get(pairKey);

        boolean occupied = structure != null && anyPlayerInStructure(players, layout, dims, structure)
            || anyPlayerInCorridor(players, layout, originX, originY, originZ);

        if (!occupied && !anyPlayerWithin(players, originX, originY, originZ, APPROACH_RANGE)) {
            return;
        }

        // Only the ENTRY carriage places the structure: it fixes where the room sits, and the EXIT
        // twin's position follows from it. An EXIT carriage approached first simply waits.
        if (structure == null && role != PortalCarriageRole.ENTRY) return;

        BlockPos structureOrigin = occupied && structure != null
            ? structure
            : ensureStructure(level, dims, pairKey, originX, originY, originZ);
        if (structureOrigin == null) return;

        // The entry twin sits at the structure's origin; the exit twin one corridor and one room along.
        BlockPos twinOrigin = role == PortalCarriageRole.ENTRY
            ? structureOrigin
            : structureOrigin.offset(PortalCarriageBuilder.exitTwinOffsetX(dims), 0, 0);

        PortalFrames frames = new PortalFrames(layout,
            new PortalFrames.Origin(originX, originY, originZ),
            new PortalFrames.Origin(twinOrigin.getX(), twinOrigin.getY(), twinOrigin.getZ()),
            role);

        // Publish for PortalEditMirror, which needs to answer "is this block in a portal corridor?"
        // on the hot path of every sub-level block change and cannot re-derive train geometry there.
        publishPairing(carriageIndex, ship, dims, originX, originY, originZ, twinOrigin);

        for (ServerPlayer player : players) {
            if (player.isPassenger()) continue;
            if (onCooldown(player, level.getGameTime())) continue;

            double px = player.getX(), py = player.getY(), pz = player.getZ();
            PortalFrames.Move move = frames.requiredMove(px, py, pz);
            if (move == null) continue;

            // A player who was standing goes to the destination's floor surface rather than to the
            // carried-across local Y — the two frames' block grids differ by the ship's fractional
            // pose, and landing a fraction inside a twin that hangs in open air drops them through it.
            double targetY = player.onGround() ? frames.floorSurfaceY(move.toFrame()) : move.y();

            player.connection.teleport(move.x(), targetY, move.z(),
                player.getYRot(), player.getXRot(), RELATIVE_ALL);
            COOLDOWNS.put(player.getUUID(), level.getGameTime() + SWAP_COOLDOWN_TICKS);

            LOGGER.info("[DungeonTrain] Portal carriage swap: player={} carriage={} → {} ({}, {}, {}) → ({}, {}, {})",
                player.getName().getString(), carriageIndex,
                move.toFrame() == PortalFrames.FRAME_TWIN ? "TWIN" : "CARRIAGE",
                fmt(px), fmt(py), fmt(pz), fmt(move.x()), fmt(targetY), fmt(move.z()));
        }
    }

    /**
     * The twin for this carriage, stamping it if there is none yet or the carriage has rolled out of
     * the chunk columns the old one sits in — which is the condition the crossing's seamlessness
     * depends on, so it is also exactly when a fresh one is worth the block writes.
     */
    private static BlockPos ensureStructure(ServerLevel level, CarriageDims dims, int pairKey,
                                            double originX, double originY, double originZ) {
        BlockPos existing = STRUCTURES.get(pairKey);
        BlockPos wanted = BlockPos.containing(originX, originY + TWIN_Y_OFFSET, originZ);

        if (wanted.getY() + dims.height() > level.getMaxBuildHeight() - CEILING_MARGIN) return existing;

        if (existing != null && horizontalDistance(existing, originX, originZ) <= TWIN_MAX_DRIFT) {
            return existing;
        }

        // Clear the outgoing structure rather than leaving it hanging in the sky. Without this the
        // train would trail abandoned corridors, a set every time a pair drifted out of range.
        if (existing != null) {
            PortalCarriageBuilder.eraseTwin(level, existing, dims);
        }

        PortalCarriageBuilder.stampPairStructure(level, wanted, dims);
        STRUCTURES.put(pairKey, wanted);
        LOGGER.info("[DungeonTrain] Stamped portal pair {} at {} (entry carriage at {}, {}, {})",
            pairKey, wanted, fmt(originX), fmt(originY), fmt(originZ));
        return wanted;
    }

    /**
     * Publish this carriage's corridor↔twin pairing for {@link PortalEditMirror}.
     *
     * <p>The carriage's blocks do not live at its world position — they are in its sub-level plot at
     * shipyard coordinates — so the world origin is converted with {@code worldToShip}, the same
     * conversion {@code CarriageBlockSnapshot} and {@code SoulCampfireHealEvents} use to reach
     * carriage blocks.</p>
     */
    private static void publishPairing(int carriageIndex, ManagedShip ship, CarriageDims dims,
                                       double originX, double originY, double originZ, BlockPos twinOrigin) {
        if (!(ship instanceof SableManagedShip sable)) return;

        LevelPlot plot = sable.subLevel().getPlot();
        if (plot == null) return;

        Vector3d shipLocal = ship.worldToShip(new Vector3d(originX, originY, originZ));
        BlockPos plotOrigin = BlockPos.containing(shipLocal.x, shipLocal.y, shipLocal.z);

        PortalPairIndex.publish(carriageIndex,
            new PortalPairIndex.Entry(plot, plotOrigin, twinOrigin, dims));
    }

    /** True if any player is anywhere inside a pair structure — either corridor, or the room between. */
    private static boolean anyPlayerInStructure(List<ServerPlayer> players, PortalCarriageLayout layout,
                                                CarriageDims dims, BlockPos structure) {
        int span = PortalCarriageBuilder.exitTwinOffsetX(dims) + dims.length();
        for (ServerPlayer player : players) {
            double dx = player.getX() - structure.getX();
            double dy = player.getY() - structure.getY();
            double dz = player.getZ() - structure.getZ();
            if (dx >= -1 && dx <= span + 1 && dy >= -1 && dy <= dims.height() + POCKET_ROOM_SLACK
                && dz >= -POCKET_ROOM_SLACK && dz <= dims.width() + POCKET_ROOM_SLACK) {
                return true;
            }
        }
        return false;
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
