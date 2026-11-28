package games.brennan.dungeontrain.client.constellation;

/**
 * The patch's soft edge as crumbling blocks: square cells on a grid fixed to the patch, each fully
 * night or fully sky, thinning out past an outline that reaches out in 3 to 5 arms, with a few loose
 * blocks beyond. The arms drift slowly round, grow, shrink, and two of them come and go, so the shape
 * keeps changing; a cell's own roll is fixed, so nothing flickers. Pure maths, unit-tested.
 */
final class ConstellationCrumble {

    /** A cell's side, in patch radii. */
    static final float CELL = 0.05f;
    /** How far past the solid core the cells thin out to nothing, in radii. */
    static final float WIDTH = 0.22f;
    /** How far an arm reaches out past the core, at most, in radii. */
    static final float ARM = 0.95f;
    /** How far the outline pulls in between arms, in radii: the patch is not round. */
    static final float INDENT = 0.24f;
    /** Arms that are always there, and arms that come and go: 3 to 5 in all. */
    static final int STEADY_ARMS = 3, ARMS = 5;
    /** The chance of a loose block in the band just past the edge. Low: a few, not a scatter. */
    static final float STRAY = 0.012f;
    /** How far past the edge loose blocks may sit, in radii. */
    static final float STRAY_REACH = 0.22f;

    private ConstellationCrumble() {}

    /** The farthest any cell can reach from the centre, in radii, for a core of {@code core}. */
    static float extent(float core) {
        return core + ARM + WIDTH + STRAY_REACH + CELL;
    }

    /**
     * Is cell {@code (i, j)} (its lower-left corner at {@code (i, j) * CELL}) night? Solid inside the
     * core; past it, kept by chance as the edge thins; a kept cell with no kept neighbour beside it
     * is dropped, so the edge breaks into clumps rather than lone dots.
     */
    static boolean on(int i, int j, float core, double seconds) {
        return on(i, j, core, outlineTable(seconds));
    }

    /** {@link #on(int, int, float, double)} with this frame's outline already worked out. */
    static boolean on(int i, int j, float core, float[] outline) {
        float t = raw(i, j, core, outline);
        if (t >= 1f) return true;
        if (t <= 0f) return false;
        return raw(i + 1, j, core, outline) > 0f || raw(i - 1, j, core, outline) > 0f
            || raw(i, j + 1, core, outline) > 0f || raw(i, j - 1, core, outline) > 0f;
    }

    static float raw(int i, int j, float core, double seconds) {
        return raw(i, j, core, outlineTable(seconds));
    }

    /** 1 for a solid cell, 0.5 for a cell kept by chance, 0 for sky. */
    static float raw(int i, int j, float core, float[] outline) {
        float x = (i + 0.5f) * CELL, y = (j + 0.5f) * CELL;
        float r = (float) Math.sqrt(x * x + y * y) - lookup(outline, (float) Math.atan2(y, x));
        if (r <= core) return 1f;
        float t = 1f - (r - core) / WIDTH;
        if (t > 0f && hash(i, j, 0) < t) return 0.5f;
        if (r <= core + WIDTH + STRAY_REACH && hash(i, j, 1) < STRAY) return 0.5f;
        return 0f;
    }

    /** How many angles the per-frame outline table holds. */
    static final int TABLE = 720;

    /** {@link #outline} at {@link #TABLE} angles round the patch, for one moment. */
    static float[] outlineTable(double seconds) {
        float[] t = new float[TABLE];
        for (int k = 0; k < TABLE; k++) t[k] = outline((float) (2 * Math.PI * k / TABLE - Math.PI), seconds);
        return t;
    }

    private static float lookup(float[] table, float angle) {
        int k = Math.round((angle + (float) Math.PI) / (float) (2 * Math.PI) * TABLE);
        return table[Math.floorMod(k, TABLE)];
    }

    /**
     * How far the outline sits past the core at {@code angle}, at time {@code seconds}: pulled in by
     * {@link #INDENT} between arms, out to {@link #ARM} along one. Smooth in time: it changes over tens
     * of seconds.
     */
    static float outline(float angle, double seconds) {
        float out = 0f;
        for (int k = 0; k < ARMS; k++) {
            float strength = armStrength(k, seconds);
            if (strength <= 0f) continue;
            double s = seconds;
            // each arm drifts round on its own slow course, and its length and width breathe
            float centre = (float) (k * (2 * Math.PI / ARMS) + 0.9 * Math.sin(s / (37.0 + 7 * k) + k * 1.7)
                + s / (160.0 + 40 * k) * (k % 2 == 0 ? 1 : -1));
            float width = 0.5f + 0.15f * (float) Math.sin(s / (23.0 + 5 * k) + k * 2.3);
            float length = ARM * strength * (0.65f + 0.35f * (float) Math.sin(s / (29.0 + 3 * k) + k * 0.8));
            float d = (float) Math.IEEEremainder(angle - centre, 2 * Math.PI);
            float bump = 1f - (d / width) * (d / width);
            if (bump > 0f) out = Math.max(out, (length + INDENT) * bump);
        }
        return out - INDENT;
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
