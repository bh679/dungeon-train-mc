package games.brennan.dungeontrain.ship.sable;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Map;
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
 * on the removed entity) and re-projects it through the carriage's live pose every tick, then runs
 * the chunk map's player move so trackers, tickets and the chunk view follow the body exactly as
 * they do for a living rider. Lets go when the carriage is gone or the player is released
 * ({@link DeadRiderGhostTracking#release}).</p>
 */
public final class DeadRiderServerCarry {

    private static final Map<ServerPlayer, Pin> PINS = new WeakHashMap<>();

    private DeadRiderServerCarry() {}

    private record Pin(SubLevel carriage, Vector3d local) {}

    /** Once per server level tick, after the carriages have moved for this tick. */
    public static void tick(ServerLevel level) {
        for (ServerPlayer player : DeadRiderAnchors.deadIn(level)) {
            Pin pin = PINS.get(player);
            if (pin == null) {
                pin = pin(player);
                if (pin == null) continue;
                PINS.put(player, pin);
                continue; // pinned where the body is now; it starts following next tick
            }
            if (!shouldKeep(true, pin.carriage().isRemoved())) {
                PINS.remove(player);
                continue;
            }
            Vector3d world = follow(pin.local(), pin.carriage().logicalPose());
            player.setPos(world.x, world.y, world.z);
            level.getChunkSource().chunkMap.move(player);
        }
    }

    /** Drop the pin for a player who respawned or left. Safe when there is none. */
    public static void release(ServerPlayer player) {
        PINS.remove(player);
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
        return new Pin(carriage, local);
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
