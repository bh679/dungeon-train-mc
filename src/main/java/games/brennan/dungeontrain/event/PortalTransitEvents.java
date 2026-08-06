package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.PortalGeometry;
import games.brennan.dungeontrain.portal.PortalRegistry;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.primitives.AABBdc;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Runs the hallway portal illusion: every tick, each player inside a portal corridor is moved to
 * the copy their side of the midpoint demands, and each copy's dummy door is kept in step with the
 * real one it mirrors.
 *
 * <p><b>Why a relative teleport.</b> The move goes out through
 * {@code connection.teleport(..., Set&lt;RelativeMovement&gt;)} with every axis flagged relative.
 * In {@code ClientPacketListener.handleMovePlayer} a relative axis keeps its velocity <i>and</i>
 * shifts {@code yo}/{@code yOld} — the render interpolation baseline — by the same delta, so the
 * camera does not smear across the jump and momentum survives it. An absolute teleport zeroes the
 * velocity on every axis it sets, which reads as a visible stutter mid-stride. X and Z are passed
 * unchanged, so their deltas are exactly zero and only Y actually moves.</p>
 *
 * <p><b>Every tick, not throttled.</b> The other scanners in this package sample every 10 ticks;
 * this one cannot. A sprinting player covers several blocks in that window, which would let them
 * reach and open a dummy door before the swap caught up.</p>
 *
 * <p><b>Doors.</b> Only one door of each pair is reachable — the near door from the NEAR copy, the
 * far door from the FAR copy — so each reachable door is the authority for the dummy that mirrors
 * it. Mirroring is what stops a door state popping as the player crosses; the proximity auto-close
 * then bounds how long an open door can expose the dead space behind its dummy twin.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalTransitEvents {

    /** All five axes relative: zero deltas on X/Z/rotation, the real move on Y. */
    private static final Set<RelativeMovement> RELATIVE_ALL = EnumSet.allOf(RelativeMovement.class);

    /**
     * A door with no player within this many blocks swings shut. Keeps an open door from exposing
     * the plugged dead space behind its dummy twin for longer than the player is actually standing
     * at it. Stateless on purpose — no timers to persist or to get out of step with the world.
     */
    private static final double DOOR_CLOSE_RADIUS = 6.0;

    /** Y headroom above a carriage's AABB that still counts as riding it, matching TrackPresenceEvents. */
    private static final double ROOF_STAND_SLACK = 1.0;

    private PortalTransitEvents() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        PortalRegistry registry = PortalRegistry.get(level);
        if (registry.isEmpty()) return;

        for (PortalGeometry geo : registry.all()) {
            boolean occupied = false;

            for (ServerPlayer player : players) {
                double px = player.getX();
                double py = player.getY();
                double pz = player.getZ();

                if (!geo.insideCorridor(px, py, pz)) continue;
                occupied = true;

                int shift = geo.requiredShift(px, py, pz);
                if (shift == 0) continue;

                // A player riding the train is a normal world-space entity server-side, so a
                // carriage passing through the corridor would otherwise have its riders yanked off
                // it. Leave them on the train and let it carry them clear.
                if (player.isPassenger() || isOnCarriage(level, px, py, pz)) continue;

                player.connection.teleport(px, py + shift, pz,
                    player.getYRot(), player.getXRot(), RELATIVE_ALL);
            }

            // Chunk reads only while someone is in the corridor — an empty portal's chunks may be
            // unloaded, and touching them would force a load.
            if (occupied) syncDoors(level, geo, players);
        }
    }

    /**
     * Mirror each reachable door onto its dummy twin, then close any door nobody is standing near.
     * Both copies therefore show the same door state at all times, which is what keeps the crossing
     * from popping.
     */
    private static void syncDoors(ServerLevel level, PortalGeometry geo, List<ServerPlayer> players) {
        int nearFloor = geo.floorYOf(PortalGeometry.COPY_NEAR);
        int farFloor = geo.floorYOf(PortalGeometry.COPY_FAR);
        int doorZ = geo.doorZ();

        // Near door: real in the NEAR copy, dummy in the FAR one.
        BlockPos nearReal = new BlockPos(geo.nearDoorX(), nearFloor + 1, doorZ);
        BlockPos nearDummy = new BlockPos(geo.nearDoorX(), farFloor + 1, doorZ);
        // Far door: real in the FAR copy, dummy in the NEAR one.
        BlockPos farReal = new BlockPos(geo.farDoorX(), farFloor + 1, doorZ);
        BlockPos farDummy = new BlockPos(geo.farDoorX(), nearFloor + 1, doorZ);

        boolean nearOpen = isOpen(level, nearReal) && anyPlayerNear(players, nearReal);
        boolean farOpen = isOpen(level, farReal) && anyPlayerNear(players, farReal);

        setDoorOpen(level, nearReal, nearOpen);
        setDoorOpen(level, nearDummy, nearOpen);
        setDoorOpen(level, farReal, farOpen);
        setDoorOpen(level, farDummy, farOpen);
    }

    private static boolean anyPlayerNear(List<ServerPlayer> players, BlockPos pos) {
        for (ServerPlayer player : players) {
            if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                <= DOOR_CLOSE_RADIUS * DOOR_CLOSE_RADIUS) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOpen(ServerLevel level, BlockPos lowerHalf) {
        if (!level.isLoaded(lowerHalf)) return false;
        BlockState state = level.getBlockState(lowerHalf);
        return state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.OPEN);
    }

    /**
     * Force both halves of a door to {@code open}. Writes with {@link Block#UPDATE_CLIENTS} only —
     * the state is being mirrored, not operated, so it should not kick off neighbour or redstone
     * churn, and the mirrored copy stays silent (the player hears their own door through vanilla).
     */
    private static void setDoorOpen(ServerLevel level, BlockPos lowerHalf, boolean open) {
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos pos = lowerHalf.above(dy);
            if (!level.isLoaded(pos)) continue;

            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof DoorBlock)) continue;
            if (state.getValue(DoorBlock.OPEN) == open) continue;

            level.setBlock(pos, state.setValue(DoorBlock.OPEN, open), Block.UPDATE_CLIENTS);
        }
    }

    /**
     * Coarse "is this position aboard a carriage" test — the same worldAABB containment
     * {@code TrackPresenceEvents.isOnAnyCarriage} uses, with roof slack so standing on top counts.
     */
    private static boolean isOnCarriage(ServerLevel level, double px, double py, double pz) {
        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            AABBdc bb = ship.worldAABB();
            if (px < bb.minX() || px > bb.maxX()) continue;
            if (py < bb.minY() || py > bb.maxY() + ROOF_STAND_SLACK) continue;
            if (pz < bb.minZ() || pz > bb.maxZ()) continue;
            return true;
        }
        return false;
    }
}
