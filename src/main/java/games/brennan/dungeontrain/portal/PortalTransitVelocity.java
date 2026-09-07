package games.brennan.dungeontrain.portal;

import net.minecraft.world.phys.Vec3;

/**
 * Takes the train's share out of a traveller's velocity when a portal corridor moves them off it.
 *
 * <p>A dimensional carriage's two halves are in different reference frames: the corridor on the
 * train is riding a Sable ship travelling down the track, and the twin it swaps you into is stamped
 * into the static world. Carry the whole velocity across that jump and a player who was merely
 * standing on the train arrives in a stationary room still doing track speed, and slides away down
 * the corridor. What they should arrive with is the velocity they would have had if the train had
 * been parked — their own walking, sprinting, jumping — and nothing else.</p>
 *
 * <p><b>Only along the train's own axis, and only ever subtracting.</b> The correction removes at
 * most the carrier's speed from the component of the traveller's velocity that points the way the
 * carrier is going; the two axes across it are left exactly as they were. The clamp is what makes
 * this safe: how much of Sable's carry shows up in an entity's {@code deltaMovement} (rather than
 * in a positional carry) is not something this class can know, so it is written to under-correct
 * rather than over-correct. Somebody running <i>against</i> the train, or standing in a
 * {@code deltaMovement} that never held the carry at all, is left alone rather than launched
 * backwards.</p>
 */
public final class PortalTransitVelocity {

    private PortalTransitVelocity() {}

    /**
     * {@code delta} with the carrier's own motion taken out of it.
     *
     * @param delta   the traveller's world-space velocity as it stands
     * @param carrier the world-space velocity of the frame it is leaving — {@link Vec3#ZERO} for a
     *                parked train, or one with no kinematic driver to ask, which returns
     *                {@code delta} untouched
     */
    public static Vec3 withoutCarrier(Vec3 delta, Vec3 carrier) {
        double speed = carrier.length();
        if (speed <= 0.0) return delta;

        Vec3 along = carrier.scale(1.0 / speed);
        double component = delta.dot(along);
        // Never negative (they are moving against the carrier — that is their own doing), never more
        // than the carrier is moving (the surplus is theirs too).
        double carried = Math.clamp(component, 0.0, speed);
        if (carried <= 0.0) return delta;

        return delta.subtract(along.scale(carried));
    }
}
