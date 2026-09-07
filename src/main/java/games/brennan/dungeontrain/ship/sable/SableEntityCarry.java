package games.brennan.dungeontrain.ship.sable;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;

/**
 * What Sable thinks is carrying an entity.
 *
 * <p>Sable does not move a rider by giving it velocity. {@code ServerPlayerMixin} in Sable's
 * {@code entities_stick_sublevels} package re-derives a tracked rider's position from its
 * sub-level's pose every tick and calls {@code setPos} — a positional carry, invisible to
 * {@code deltaMovement}. So "is this player still being carried?" is a question only Sable can
 * answer, and it is the question a portal swap has to ask: the twin is stamped into the static
 * world, and anybody still attached to the carriage they left goes on being pushed along inside
 * it.</p>
 *
 * <p>Lives here rather than in {@code portal} for the reason the rest of {@code ship.sable} does:
 * one place holds the Sable types, and the callers stay portable across physics backends.</p>
 */
public final class SableEntityCarry {

    private SableEntityCarry() {}

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
