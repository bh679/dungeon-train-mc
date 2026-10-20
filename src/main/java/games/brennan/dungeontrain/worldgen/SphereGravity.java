package games.brennan.dungeontrain.worldgen;

import java.util.List;

/**
 * Sphere gravity: the pull every floating sphere of the spheres band exerts on things near it, like a
 * small planet. Pure (no Minecraft types), so the falloff is unit-testable and both sides compute the
 * same pull from the same seed-stable {@link SphereField}.
 *
 * <p>The pull is an extra acceleration toward the sphere's centre, added on top of vanilla gravity —
 * not a re-pointed "down". Per sphere of radius {@code r} at distance {@code d}:</p>
 * <ul>
 *   <li><b>Surface pull</b> {@code g = strength × 0.08 × size(r)}: 0.08 is vanilla's gravity per tick, and
 *       {@code size} grows with the radius up to 1 at {@link #FULL_SIZE_RADIUS}, so a big sphere at
 *       strength 1 pulls as hard as the ground does while a small one only nudges.</li>
 *   <li><b>Outside</b> ({@code d ≥ r}): holds near the surface pull at first and dives to 0 at the reach
 *       ({@code reachRadii × r}): with {@code t} the fraction of the way from the surface to the reach, the pull
 *       is {@code g × (1 − t^hold)}. {@code hold} 1 is a straight ramp down; 3 (the default) is still 88% half
 *       way out and 49% at 80%, then drops fast — slow at first, quick at the end, not a planet's inverse
 *       square (which is gone within a couple of radii).</li>
 *   <li><b>Inside</b> ({@code d < r}): held at the surface pull, no stronger. A sphere's ground is usually
 *       well inside its radius — an overworld sphere's top is the natural surface, about 0.3·r above the
 *       centre — so this is what you feel when you jump off it.</li>
 *   <li><b>Standing on it, or swimming in it</b>: on the ground or in water/lava inside the sphere or within
 *       {@link #STAND_MARGIN} of its surface (its top, a tunnel, the train's corridor, its lakes) — no pull, so
 *       walking and swimming feel normal and only a jump brings the pull back. Only that sphere lets go; others
 *       still pull.</li>
 * </ul>
 * Pulls from overlapping spheres add, but only the {@link #MAX_SPHERES} strongest at that spot count, so a dense
 * cluster can't stack into more than a few surface pulls. Each sphere's pull eases off as the thing speeds toward its centre,
 * reaching 0 at {@link #MAX_SPEED_TOWARD} blocks/tick — a smooth taper rather than an on/off cut, which
 * flickered tick to tick near the limit — so nothing builds runaway speed.
 */
public final class SphereGravity {

    /** Vanilla's per-tick gravity for living entities (blocks/tick²). */
    public static final double VANILLA_G = 0.08;
    /** Radius at which a sphere pulls at the full configured strength. */
    public static final double FULL_SIZE_RADIUS = 40.0;
    /** Smallest size factor — even the tiniest sphere is felt a little up close. */
    public static final double MIN_SIZE = 0.15;
    /** Speed toward a sphere's centre (blocks/tick) at which its pull has eased off to nothing. */
    public static final double MAX_SPEED_TOWARD = 0.6;
    /**
     * How far past a sphere's surface something on the ground still counts as standing on it. Generous: a
     * sphere's surface is blocky terrain, and its upper part is open air above the cut surface.
     */
    public static final double STAND_MARGIN = 3.0;
    /** At most this many spheres pull on one thing at once: the strongest at its spot. Bounds a dense cluster's stack. */
    public static final int MAX_SPHERES = 3;

    private SphereGravity() {}

    /** An acceleration in blocks/tick². */
    public record Pull(double x, double y, double z) {
        public static final Pull ZERO = new Pull(0, 0, 0);

        public double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }

        public Pull scale(double k) {
            return k == 1.0 ? this : new Pull(x * k, y * k, z * k);
        }

        public boolean isZero() {
            return x == 0 && y == 0 && z == 0;
        }
    }

    /** Pull at the surface of a sphere of radius {@code r}. */
    public static double surfacePull(int r, double strength) {
        double size = Math.max(MIN_SIZE, Math.min(1.0, r / FULL_SIZE_RADIUS));
        return strength * VANILLA_G * size;
    }

    /** The hold the test-only short forms use: a straight ramp from the surface pull to 0 at the reach. */
    static final double LINEAR_HOLD = 1.0;

    /** {@link #pullAt(double, int, double, double, double)} with a straight ramp down to the reach. */
    public static double pullAt(double d, int r, double strength, double reachRadii) {
        return pullAt(d, r, strength, reachRadii, LINEAR_HOLD);
    }

    /**
     * Magnitude of a sphere's pull at distance {@code d} from its centre: {@code g × (1 − t^hold)} outside, where
     * {@code t} runs 0 at the surface to 1 at the reach; higher {@code hold} keeps the pull up longer and drops it
     * harder at the end.
     */
    public static double pullAt(double d, int r, double strength, double reachRadii, double hold) {
        if (r <= 0 || strength <= 0) return 0.0;
        double g = surfacePull(r, strength);
        if (d < r) return g;
        double reach = Math.max(1.0, reachRadii) * r;
        if (d >= reach) return 0.0;
        double t = (d - r) / (reach - r);
        return g * (1.0 - Math.pow(t, Math.max(1.0, hold)));
    }

    /** {@code 1} while not closing on the centre, easing linearly to {@code 0} at {@link #MAX_SPEED_TOWARD}. */
    static double speedEase(double speedToward) {
        if (speedToward <= 0) return 1.0;
        return Math.max(0.0, 1.0 - speedToward / MAX_SPEED_TOWARD);
    }

    /** Extra distance past the largest radius a lookup must cover for {@code reachRadii}. */
    public static double lookupReach(int rMax, double reachRadii) {
        return Math.max(0.0, reachRadii - 1.0) * Math.max(0, rMax);
    }

    /** {@link #pull(List, double, double, double, double, double, double, boolean, double, double, double)} with a straight ramp down to the reach. */
    public static Pull pull(List<SphereField.Sphere> spheres, double x, double y, double z,
                            double vx, double vy, double vz, boolean held,
                            double strength, double reachRadii) {
        return pull(spheres, x, y, z, vx, vy, vz, held, strength, reachRadii, LINEAR_HOLD);
    }

    /**
     * Summed pull of {@code spheres} on a point {@code (x, y, z)} moving at {@code (vx, vy, vz)};
     * {@code held} (on the ground or in a fluid) lets go of any sphere it is standing or swimming in.
     */
    public static Pull pull(List<SphereField.Sphere> spheres, double x, double y, double z,
                            double vx, double vy, double vz, boolean held,
                            double strength, double reachRadii, double hold) {
        if (spheres.isEmpty() || strength <= 0) return Pull.ZERO;
        // The MAX_SPHERES strongest pulls, kept sorted strongest first; a weaker one drops off the end.
        double[] mag = new double[MAX_SPHERES];
        double[] px = new double[MAX_SPHERES], py = new double[MAX_SPHERES], pz = new double[MAX_SPHERES];
        int kept = 0;
        for (SphereField.Sphere s : spheres) {
            double dx = s.cx() + 0.5 - x, dy = s.cy() + 0.5 - y, dz = s.cz() + 0.5 - z;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < 1e-6 || (held && d <= s.r() + STAND_MARGIN)) continue;
            double ux = dx / d, uy = dy / d, uz = dz / d;
            double ease = speedEase(vx * ux + vy * uy + vz * uz);
            if (ease <= 0) continue;
            double a = pullAt(d, s.r(), strength, reachRadii, hold) * ease;
            if (a <= 0 || (kept == MAX_SPHERES && a <= mag[MAX_SPHERES - 1])) continue;
            int i = Math.min(kept, MAX_SPHERES - 1);
            while (i > 0 && mag[i - 1] < a) {
                mag[i] = mag[i - 1]; px[i] = px[i - 1]; py[i] = py[i - 1]; pz[i] = pz[i - 1];
                i--;
            }
            mag[i] = a; px[i] = ux * a; py[i] = uy * a; pz[i] = uz * a;
            if (kept < MAX_SPHERES) kept++;
        }
        if (kept == 0) return Pull.ZERO;
        double ax = 0, ay = 0, az = 0;
        for (int i = 0; i < kept; i++) {
            ax += px[i];
            ay += py[i];
            az += pz[i];
        }
        return new Pull(ax, ay, az);
    }
}
