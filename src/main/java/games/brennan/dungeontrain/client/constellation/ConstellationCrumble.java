package games.brennan.dungeontrain.client.constellation;

/**
 * The patch's soft edge as crumbling blocks: square cells on a grid fixed to the patch, each fully
 * night or fully sky. Round the solid core a thin crumbling rim, and out of it 3 to 5 arms that bend
 * as they reach out and taper to a crumbling tip. Each arm drifts slowly round, grows and shrinks,
 * bends one way then the other, and slowly thickens and thins (slender to about two and a half times
 * that, a thicker arm reaching less far); two of them come and go. A cell's own roll is fixed, so
 * nothing flickers. Pure maths, unit-tested.
 */
final class ConstellationCrumble {

    /** A cell's side, in patch radii. */
    static final float CELL = 0.06f;
    /** The crumbling rim round the core: from this far inside it to this far outside, in radii. */
    static final float RIM_IN = 0.06f, RIM_OUT = 0.12f;
    /** How far the longest arm reaches past the core, in radii. */
    static final float ARM = 1.0f;
    /** An arm's half-width, as an angle times radius: at its root, and the least it tapers to. */
    static final float ROOT_WIDTH = 0.11f, TIP_WIDTH = 0.045f;
    /** How thick an arm gets, at most, against its slenderest. */
    static final float MAX_THICKNESS = 2.5f;
    /** How far an arm bends by its tip, at most, in radians. */
    static final float BEND = 1.8f;
    /** Arms that are always there, and arms that come and go: 3 to 5 in all. */
    static final int STEADY_ARMS = 3, ARMS = 5;
    /** The chance of a loose block in the band just past the core. Low: a few, not a scatter. */
    static final float STRAY = 0.01f;

    /** One arm, for one moment: where it leaves the core, how it bends, how long and thick it is. */
    record Arm(float base, float bend, float length, float thickness) {}

    private ConstellationCrumble() {}

    /** The farthest any cell can reach from the centre, in radii, for a core of {@code core}. */
    static float extent(float core) {
        return core + ARM + RIM_OUT + CELL;
    }

    static boolean on(int i, int j, float core, double seconds) {
        return on(i, j, core, arms(seconds));
    }

    /**
     * Is cell {@code (i, j)} (its lower-left corner at {@code (i, j) * CELL}) night? Solid inside the
     * core; past it, kept by chance as the rim and the arms thin; a kept cell with no kept neighbour
     * beside it is dropped, so the edge breaks into clumps rather than lone dots.
     */
    static boolean on(int i, int j, float core, Arm[] arms) {
        float t = raw(i, j, core, arms);
        if (t >= 1f) return true;
        if (t <= 0f) return false;
        return raw(i + 1, j, core, arms) > 0f || raw(i - 1, j, core, arms) > 0f
            || raw(i, j + 1, core, arms) > 0f || raw(i, j - 1, core, arms) > 0f;
    }

    static float raw(int i, int j, float core, double seconds) {
        return raw(i, j, core, arms(seconds));
    }

    /** 1 for a solid cell, 0.5 for a cell kept by chance, 0 for sky. */
    static float raw(int i, int j, float core, Arm[] arms) {
        float x = (i + 0.5f) * CELL, y = (j + 0.5f) * CELL;
        float r = (float) Math.sqrt(x * x + y * y);
        if (r < core - RIM_IN) return 1f;
        if (r < core + RIM_OUT) {
            float keep = 1f - (r - core + RIM_IN) / (RIM_IN + RIM_OUT);
            if (hash(i, j, 0) < keep) return 0.5f;
        }
        float arm = armAt(r, (float) Math.atan2(y, x), core, arms);
        if (arm > 0f && hash(i, j, 1) < 0.55f + 0.45f * arm) return 0.5f;
        if (r < core + RIM_OUT + 0.15f && hash(i, j, 2) < STRAY) return 0.5f;
        return 0f;
    }

    /** How solid the arms are at {@code (r, angle)}: 1 at a root, less toward a tip, 0 off them. */
    static float armAt(float r, float angle, float core, Arm[] arms) {
        float best = 0f;
        for (Arm a : arms) {
            float u = (r - core) / a.length();
            if (u < 0f || u > 1f) continue;
            float centre = a.base() + a.bend() * u;
            float halfWidth = (ROOT_WIDTH * (1f - u) + TIP_WIDTH) * a.thickness();
            float d = Math.abs((float) Math.IEEEremainder(angle - centre, 2 * Math.PI)) * r;
            if (d < halfWidth) best = Math.max(best, 1f - 0.6f * u);
        }
        return best;
    }

    /** The arms at time {@code seconds}: slow, smooth, 3 to 5 of them. */
    static Arm[] arms(double seconds) {
        java.util.List<Arm> out = new java.util.ArrayList<>(ARMS);
        for (int k = 0; k < ARMS; k++) {
            float strength = armStrength(k, seconds);
            if (strength <= 0.02f) continue;
            double s = seconds;
            float base = (float) (k * (2 * Math.PI / ARMS) + 0.9 * Math.sin(s / (37.0 + 7 * k) + k * 1.7)
                + s / (160.0 + 40 * k) * (k % 2 == 0 ? 1 : -1));
            float bend = BEND * (float) Math.sin(s / (19.0 + 4 * k) + k * 1.3);
            float thickness = 1f + (MAX_THICKNESS - 1f) * (0.5f + 0.5f * (float) Math.sin(s / (53.0 + 9 * k) + k * 2.1));
            // a thicker arm reaches less far, so a thick arm never trails a long thin tail
            float reach = 1f - 0.2f * (thickness - 1f);
            float length = ARM * strength * reach * (0.7f + 0.3f * (float) Math.sin(s / (29.0 + 3 * k) + k * 0.8));
            out.add(new Arm(base, bend, Math.max(0.1f, length), thickness));
        }
        return out.toArray(new Arm[0]);
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
