package games.brennan.dungeontrain.client.constellation;

/**
 * The patch's soft edge as crumbling blocks: square cells on a grid fixed to the patch, each fully
 * night or fully sky.
 *
 * <ul>
 *   <li>The solid middle's edge ripples: six waves of their own sizes, each growing and shrinking and
 *       drifting at its own pace, so the edge never settles into an even pattern.</li>
 *   <li>Out of it reach 3 to 5 slender arms (three always, two that come and go). Each leaves the
 *       circle at a point that drifts one way round, and leans over to its own side, never flipping.
 *       An arm is longest standing straight out and shorter the further it leans; a thicker arm is
 *       shorter too. It narrows toward its tip and crumbles there.</li>
 * </ul>
 *
 * <p>Time runs on {@link #clock()}, sped up or slowed by the dev command. A cell's own roll is fixed,
 * so nothing flickers. Pure maths apart from the clock, unit-tested.</p>
 */
final class ConstellationCrumble {

    /** A cell's side, in patch radii. */
    static final float CELL = 0.06f;
    /** The crumbling rim round the middle: from this far inside its edge to this far outside, in radii. */
    static final float RIM_IN = 0.06f, RIM_OUT = 0.12f;
    /** How long the longest arm is, along its length, in radii. */
    static final float ARM = 0.9f;
    /** An arm's half-width at its root, and what it narrows to at its tip, in radii (before thickness). */
    static final float ROOT_WIDTH = 0.0528f, TIP_WIDTH = 0.0336f;
    /** How thick an arm gets, at most, against its slenderest. */
    static final float MAX_THICKNESS = 2.5f;
    /** How far an arm leans over, at most, in radians off straight out. */
    static final float LEAN = 1.15f;
    /** How much shorter an arm gets fully leaned over: 0.6 leaves 40% of its length. */
    static final float LEAN_SHORTEN = 0.6f;
    /** Arms that are always there, and arms that come and go: 3 to 5 in all. */
    static final int STEADY_ARMS = 3, ARMS = 5;
    /** How many straight pieces an arm is drawn with along its length. */
    static final int SEGMENTS = 10;
    /** How strong the waves on the middle's edge are. */
    static final float WAVE_SCALE = 1.7f;

    /** The edge waves: {frequency, size, drift period, size period, phase}. */
    private static final double[][] WAVES = {
        {2, 0.040, 17, 23, 0.3}, {3, 0.050, 23, 37, 1.1}, {4, 0.030, 29, 19, 2.0},
        {5, 0.035, 31, 43, 2.7}, {7, 0.025, 13, 29, 0.6}, {9, 0.018, 11, 53, 1.9},
    };

    /** One arm, for one moment: its centre line from the circle outward, and its thickness. */
    record Arm(float[] xs, float[] ys, float thickness, float minX, float minY, float maxX, float maxY) {}

    // ---- the clock ----------------------------------------------------------------------------------

    /** How fast the shape changes: 1 is the designed pace. Set by the dev command. */
    private static volatile double speed = 1.0;
    private static double clock = 1500.0;
    private static long lastMs = -1;

    static double speed() {
        return speed;
    }

    static void setSpeed(double value) {
        speed = Math.max(0.0, value);
    }

    /** The shape's time, in seconds; it runs at {@link #speed()} and never jumps when the speed changes. */
    static double clock() {
        long now = net.minecraft.Util.getMillis();
        if (lastMs >= 0) clock += Math.min(250, now - lastMs) / 1000.0 * speed;
        lastMs = now;
        return clock;
    }

    private ConstellationCrumble() {}

    // ---- the shape ----------------------------------------------------------------------------------

    /** The farthest any cell can reach from the centre, in radii, for a middle of {@code core}. */
    static float extent(float core) {
        return core + waveMax() + ARM + RIM_OUT + CELL;
    }

    private static float waveMax() {
        float m = 0f;
        for (double[] w : WAVES) m += (float) w[1];
        return m * WAVE_SCALE;
    }

    static boolean on(int i, int j, float core, double seconds) {
        return on(i, j, core, arms(core, seconds), seconds);
    }

    /**
     * Is cell {@code (i, j)} (its lower-left corner at {@code (i, j) * CELL}) night? Solid inside the
     * middle; past it, kept by chance as the rim and the arms thin; a kept cell with no kept
     * neighbour beside it is dropped, so the edge breaks into clumps rather than lone dots.
     */
    static boolean on(int i, int j, float core, Arm[] arms, double seconds) {
        float t = raw(i, j, core, arms, seconds);
        if (t >= 1f) return true;
        if (t <= 0f) return false;
        return raw(i + 1, j, core, arms, seconds) > 0f || raw(i - 1, j, core, arms, seconds) > 0f
            || raw(i, j + 1, core, arms, seconds) > 0f || raw(i, j - 1, core, arms, seconds) > 0f;
    }

    /** 1 for a solid cell, 0.5 for a cell kept by chance, 0 for sky. */
    static float raw(int i, int j, float core, Arm[] arms, double seconds) {
        float x = (i + 0.5f) * CELL, y = (j + 0.5f) * CELL;
        float r = (float) Math.sqrt(x * x + y * y);
        float edge = core + wave((float) Math.atan2(y, x), seconds);
        if (r < edge - RIM_IN) return 1f;
        if (r < edge + RIM_OUT) {
            float keep = 1f - (r - edge + RIM_IN) / (RIM_IN + RIM_OUT);
            if (hash(i, j, 0) < keep) return 0.5f;
        }
        float arm = armAt(x, y, arms);
        if (arm > 0f && hash(i, j, 1) < 0.55f + 0.45f * arm) return 0.5f;
        return 0f;
    }

    /** How far the middle's edge sits out (or in) at {@code angle}, at time {@code seconds}. */
    static float wave(float angle, double seconds) {
        double w = 0;
        for (double[] v : WAVES) {
            double f = v[0], size = v[1], drift = v[2], grow = v[3], phase = v[4];
            double amp = size * (0.35 + 0.65 * (0.5 + 0.5 * Math.sin(seconds / grow + phase * 2.3)));
            double way = ((int) f) % 2 == 1 ? 1 : -1;
            w += amp * Math.sin(f * angle + seconds / drift * way + phase + 0.8 * Math.sin(seconds / (drift * 1.7) + phase));
        }
        return (float) (w * WAVE_SCALE);
    }

    /** How solid the arms are at {@code (x, y)}: 1 at a root, less toward a tip, 0 off them. */
    static float armAt(float x, float y, Arm[] arms) {
        float best = 0f;
        for (Arm a : arms) {
            float pad = (ROOT_WIDTH + TIP_WIDTH) * a.thickness();
            if (x < a.minX() - pad || x > a.maxX() + pad || y < a.minY() - pad || y > a.maxY() + pad) continue;
            for (int q = 0; q < SEGMENTS; q++) {
                float ax = a.xs()[q], ay = a.ys()[q], dx = a.xs()[q + 1] - ax, dy = a.ys()[q + 1] - ay;
                float l2 = dx * dx + dy * dy;
                if (l2 <= 0f) continue;
                float t = Math.max(0f, Math.min(1f, ((x - ax) * dx + (y - ay) * dy) / l2));
                float ex = x - ax - t * dx, ey = y - ay - t * dy;
                float u = (q + t) / SEGMENTS;
                float half = (ROOT_WIDTH * (1f - u) + TIP_WIDTH) * a.thickness();
                if (ex * ex + ey * ey < half * half) best = Math.max(best, 1f - 0.6f * u);
            }
        }
        return best;
    }

    /** The arms at time {@code seconds}, leaving a middle of radius {@code core}: 3 to 5 of them. */
    static Arm[] arms(float core, double seconds) {
        java.util.List<Arm> out = new java.util.ArrayList<>(ARMS);
        double s = seconds;
        for (int k = 0; k < ARMS; k++) {
            float strength = armStrength(k, s);
            if (strength <= 0.02f) continue;
            double base = k * (2 * Math.PI / ARMS) + s / (120.0 + 25 * k); // drifts one way, never back
            int side = k % 2 == 0 ? 1 : -1;                                // leans to its own side, always
            float lean = 0.5f + 0.5f * (float) Math.sin(s / (19.0 + 4 * k) + k * 1.3);
            float thickness = 1f + (MAX_THICKNESS - 1f) * (0.5f + 0.5f * (float) Math.sin(s / (53.0 + 9 * k) + k * 2.1));
            float reach = 1f - 0.2f * (thickness - 1f);
            float length = Math.max(0.06f, ARM * strength * reach * (1f - LEAN_SHORTEN * lean)
                * (0.85f + 0.15f * (float) Math.sin(s / (29.0 + 3 * k) + k * 0.8)));
            out.add(trace(base, side * LEAN * lean, length, thickness, core));
        }
        return out.toArray(new Arm[0]);
    }

    /** An arm's centre line: from the circle at {@code base}, turning to {@code tilt} off straight out. */
    private static Arm trace(double base, float tilt, float length, float thickness, float core) {
        float[] xs = new float[SEGMENTS + 1], ys = new float[SEGMENTS + 1];
        double x = Math.cos(base) * core, y = Math.sin(base) * core;
        xs[0] = (float) x;
        ys[0] = (float) y;
        float minX = xs[0], minY = ys[0], maxX = xs[0], maxY = ys[0];
        for (int q = 1; q <= SEGMENTS; q++) {
            double u = q / (double) SEGMENTS;
            double heading = Math.atan2(y, x) + tilt * Math.min(1.0, u * 1.6);
            x += Math.cos(heading) * length / SEGMENTS;
            y += Math.sin(heading) * length / SEGMENTS;
            xs[q] = (float) x;
            ys[q] = (float) y;
            minX = Math.min(minX, xs[q]);
            minY = Math.min(minY, ys[q]);
            maxX = Math.max(maxX, xs[q]);
            maxY = Math.max(maxY, ys[q]);
        }
        return new Arm(xs, ys, thickness, minX, minY, maxX, maxY);
    }

    /**
     * How much of arm {@code k} there is, 0 to 1. The first {@link #STEADY_ARMS} are always out (at
     * least half); the rest grow and fade away over a minute or so, so the count runs 3 to 5.
     */
    static float armStrength(int k, double seconds) {
        float wave = (float) Math.sin(seconds / (41.0 + 11 * k) + k * 2.9);
        if (k < STEADY_ARMS) return 0.75f + 0.25f * wave;
        return Math.max(0f, wave);
    }

    /** A steady 0..1 per cell. */
    static float hash(int i, int j, int salt) {
        int h = i * 73856093 ^ j * 19349663 ^ salt * 83492791;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }
}
