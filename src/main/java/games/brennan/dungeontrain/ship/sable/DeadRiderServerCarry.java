package games.brennan.dungeontrain.ship.sable;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.TicketType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Map;
import java.util.UUID;
import java.util.Comparator;
import java.util.WeakHashMap;

/**
 * Keeps a dead rider's server body on the carriage they died on until they respawn.
 *
 * <p>Vanilla removes the dead {@link ServerPlayer} at death tick 20 and never ticks it again, so
 * Sable's server carry stops and the body freezes at the death spot while the client body keeps
 * riding ({@code client.DeadRiderCarry}). Everything on the server that measures from the rider's
 * position then measures from a point the train leaves behind at train speed: entity tracking
 * range (mobs on the train drop off the dead client's screen about a minute later at 2 blocks/s),
 * the chunk tracking view and player chunk tickets, Sable's tracking range, mob despawn distance
 * and the train keep-alive. A mob that walks into range of the moving carriage is never sent.</p>
 *
 * <p>So, from the first tick the player is a {@link DeadRiderAnchors dead anchor}, this pins their
 * ship-local position on the carriage Sable was tracking at death (the tracking sub-level stays set
 * on the removed entity) and re-projects it through the carriage's live pose every tick for the entity
 * trackers. The chunk view and tickets stay at the death spot (the scene's cameras live there),
 * held by a chunk ticket until respawn. Lets go when the carriage is gone or the player is released
 * ({@link DeadRiderGhostTracking#release}).</p>
 */
public final class DeadRiderServerCarry {

    private static final Map<ServerPlayer, Pin> PINS = new WeakHashMap<>();

    /** Chunks held loaded around the death spot (radius, in chunks) until respawn. */
    private static final int DEATH_SPOT_TICKET_RADIUS = 4;
    private static final TicketType<UUID> DEATH_SPOT_TICKET =
        TicketType.create("dungeontrain_death_spot", Comparator.comparing(UUID::toString));

    private DeadRiderServerCarry() {}

    private record Pin(SubLevel carriage, Vector3d local, Vec3 deathSpot) {}

    /** Once per server level tick, after the carriages have moved for this tick. */
    public static void tick(ServerLevel level) {
        for (ServerPlayer player : DeadRiderAnchors.deadIn(level)) {
            Pin pin = PINS.get(player);
            if (pin == null) {
                pin = pin(player);
                if (pin == null) continue;
                PINS.put(player, pin);
                level.getChunkSource().addRegionTicket(DEATH_SPOT_TICKET, new ChunkPos(BlockPos.containing(pin.deathSpot())),
                    DEATH_SPOT_TICKET_RADIUS, player.getUUID());
                continue; // pinned where the body is now; it starts following next tick
            }
            if (!shouldKeep(true, pin.carriage().isRemoved())) {
                release(player);
                continue;
            }
            Vector3d world = follow(pin.local(), pin.carriage().logicalPose());
            // The body rides for the entity trackers; the chunk view and player ticket stay where
            // they died (no chunk map move), with the death spot ticket below holding its chunks.
            player.setPos(world.x, world.y, world.z);
        }
    }

    /** Drop the pin (and the death spot's chunk ticket) for a player who respawned or left. Safe when there is none. */
    public static void release(ServerPlayer player) {
        Pin pin = PINS.remove(player);
        if (pin != null) {
            player.serverLevel().getChunkSource().removeRegionTicket(DEATH_SPOT_TICKET,
                new ChunkPos(BlockPos.containing(pin.deathSpot())), DEATH_SPOT_TICKET_RADIUS, player.getUUID());
        }
    }

    /**
     * Where this player died, in world space, while their body is carried: the train's keep-alive
     * measures from here, so the line behaves as if they still stood at that spot (the train rolls
     * past and keeps extending behind itself). Null when the body is not carried.
     */
    public static Vec3 deathSpot(ServerPlayer player) {
        Pin pin = PINS.get(player);
        return pin == null ? null : pin.deathSpot();
    }

    /** True while this player's body is being carried. */
    public static boolean isCarried(ServerPlayer player) {
        return PINS.containsKey(player);
    }

    private static Pin pin(ServerPlayer player) {
        SubLevel carriage = Sable.HELPER.getTrackingSubLevel(player);
        if (carriage == null || carriage.isRemoved()) return null;
        Vector3d local = carriage.logicalPose().transformPositionInverse(
            new Vector3d(player.getX(), player.getY(), player.getZ()));
        return new Pin(carriage, local, player.position());
    }

    /** Pure: the pinned ship-local spot in world space under the carriage's current pose. */
    static Vector3d follow(Vector3dc local, Pose3dc pose) {
        return pose.transformPosition(new Vector3d(local));
    }

    /** Pure: a pin survives only while the player is still a dead anchor on a live carriage. */
    static boolean shouldKeep(boolean stillDeadAnchor, boolean carriageRemoved) {
        return stillDeadAnchor && !carriageRemoved;
    }
}
