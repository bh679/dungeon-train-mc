package games.brennan.dungeontrain.ship.sable;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LivingEntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;

/**
 * What Sable thinks is carrying an entity — and how to make it stop.
 *
 * <p>Sable does not move a rider by giving it {@code deltaMovement}. It keeps two things of its own
 * on the entity instead, and both outlive a teleport:</p>
 * <ul>
 *   <li><b>A tracking sub-level.</b> {@code entities_stick_sublevels/ServerPlayerMixin.tick}
 *       re-derives a tracked rider's position from that sub-level's pose every tick and calls
 *       {@code setPos} — a positional carry. It lets go by itself once the rider is found to be
 *       elsewhere, but not before pushing them one more tick's worth.</li>
 *   <li><b>An inherited velocity.</b> {@code entity_sublevel_collision/LivingEntityMixin} gives
 *       every living entity standing on a sub-level that sub-level's velocity (in blocks per tick),
 *       applies it to the position separately from {@code deltaMovement}, and decays it at 0.7 a
 *       tick on the ground. It is what lets a rider step off a moving ship and keep going for a
 *       moment — and it is exactly what a portal swap must not carry across: dropped into a room
 *       stamped into the static world, a player who was standing still slid 0.100, 0.069, 0.048,
 *       0.033… blocks a tick until it had drained. Those numbers are the dev-client log; the first
 *       is the train's speed to three places and the ratio is Sable's drag constant.</li>
 * </ul>
 * <p>Neither is visible in {@code deltaMovement}, so no amount of correcting that can fix it —
 * which is what two rounds of trying taught. The rider's own walking <i>is</i> in
 * {@code deltaMovement}, and is left exactly alone.</p>
 *
 * <p>Lives here rather than in {@code portal} for the reason the rest of {@code ship.sable} does:
 * one place holds the Sable types, and the callers stay portable across physics backends.</p>
 */
public final class SableEntityCarry {

    private SableEntityCarry() {}

    /**
     * Take the carriage's motion off {@code entity}, leaving everything it was doing under its own
     * steam.
     *
     * <p>Zeroes the inherited velocity and drops the tracking sub-level. Safe to call for anything —
     * an entity Sable is not carrying has a zero inherited velocity and no tracking sub-level, and
     * both writes are no-ops on it. Called on both sides for a player: the server for its own view,
     * the client because that is where a player's movement is actually decided.</p>
     *
     * @return what was being shed — the carrier's id, or {@code "none"} — for the swap log
     */
    public static String shed(Entity entity) {
        String was = carrierName(entity);
        if (entity instanceof LivingEntityMovementExtension living) {
            living.sable$getInheritedVelocity().zero();
        }
        if (entity instanceof EntityMovementExtension moving) {
            moving.sable$setTrackingSubLevel(null);
        }
        return was;
    }

    /** The sub-level Sable is currently carrying {@code entity} on, or {@code null} for none. */
    public static SubLevel carrier(Entity entity) {
        return Sable.HELPER.getTrackingSubLevel(entity);
    }

    /**
     * A short name for whatever is carrying {@code entity}, for a log line — {@code "none"} when
     * nothing is, which is the answer a player standing in a twin corridor should give.
     */
    public static String carrierName(Entity entity) {
        SubLevel sub = carrier(entity);
        if (sub == null) return "none";
        return sub.getUniqueId() + (sub.isRemoved() ? " (removed)" : "");
    }
}
