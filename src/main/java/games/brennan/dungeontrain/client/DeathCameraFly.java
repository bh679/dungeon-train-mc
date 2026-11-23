package games.brennan.dungeontrain.client;

import net.minecraft.util.Mth;
import org.joml.Vector3d;

/**
 * The death moment's one camera move between shots: from the orbit of where you died, the lens
 * flies through the world to the orbit of who killed you instead of cutting through black. Pure
 * maths; {@link DeathCameraCycle} owns the state.
 */
final class DeathCameraFly {

    /** How long the flight takes (0.75 s), at the start of the killer shot. */
    static final int FLY_TICKS = 15;
    /** The lens rises this high at the middle of a long flight, to clear floors and railings. */
    static final double FLY_LIFT = 1.5;
    /** A flight this long or longer gets the full lift; shorter hops rise less. */
    static final double FULL_LIFT_DISTANCE = 8.0;

    private DeathCameraFly() {}

    /** Pure: the shot after {@code prev} is flown into, not cut to: the spot orbit to the killer orbit. */
    static boolean fliesInto(DeathShot prev, DeathShot next) {
        if (prev == null || next == null) return false;
        boolean spot = prev.kind() == DeathShot.Kind.ORBIT
            && (prev.target() == DeathShot.Target.BODY || prev.target() == DeathShot.Target.HAZARD);
        return spot && next.kind() == DeathShot.Kind.ORBIT && next.target() == DeathShot.Target.KILLER;
    }

    /** Pure: how far through the flight {@code t} ticks in is, 0 to 1. */
    static double progress(double t) {
        return Mth.clamp(t / FLY_TICKS, 0.0, 1.0);
    }

    /** Pure: smoothstep, slow off the mark and slow into the landing. */
    static double ease(double f) {
        double c = Mth.clamp(f, 0.0, 1.0);
        return c * c * (3.0 - 2.0 * c);
    }

    /** Pure: the lens {@code f} of the way through the flight, eased, rising over its middle. */
    static Vector3d lens(Vector3d from, Vector3d to, double f) {
        Vector3d at = new Vector3d(from).lerp(to, ease(f));
        double lift = FLY_LIFT * Mth.clamp(from.distance(to) / FULL_LIFT_DISTANCE, 0.0, 1.0);
        at.y += Math.sin(Math.PI * Mth.clamp(f, 0.0, 1.0)) * lift;
        return at;
    }

    /** Pure: the point the lens looks at {@code f} of the way through: it pans from one subject to the other. */
    static Vector3d aim(Vector3d from, Vector3d to, double f) {
        return new Vector3d(from).lerp(to, ease(f));
    }

    /**
     * Pure: true when an orbit swept from {@code start} by {@code sweep} radians should run the
     * other way, because its far end is nearer the lens it is flown in from (a shorter flight).
     */
    static boolean reverseSweep(Vector3d subject, double radius, double height, double start, double sweep, Vector3d from) {
        Vector3d a = DeathCameraCycle.orbitLens(subject, radius, height, start);
        Vector3d b = DeathCameraCycle.orbitLens(subject, radius, height, start + sweep);
        return b.distanceSquared(from) < a.distanceSquared(from);
    }
}
