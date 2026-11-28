package games.brennan.dungeontrain.client.constellation;

/**
 * The patch's soft edge as crumbling blocks: square cells on a grid fixed to the patch, each fully
 * night or fully sky, thinning out past a ragged outline, with a few loose blocks beyond. Cells are
 * picked by position alone, so the pattern holds still frame to frame. Pure maths, unit-tested.
 */
final class ConstellationCrumble {

    /** A cell's side, in patch radii. */
    static final float CELL = 0.05f;
    /** How far past the solid core the cells thin out to nothing, in radii. */
    static final float WIDTH = 0.34f;
    /** How far the ragged outline reaches out past the core, at most, in radii. */
    static final float WOBBLE = 0.12f;
    /** The chance of a loose block in the band just past the edge. Low: a few, not a scatter. */
    static final float STRAY = 0.012f;
    /** How far past the edge loose blocks may sit, in radii. */
    static final float STRAY_REACH = 0.22f;

    private ConstellationCrumble() {}

    /** The farthest any cell can reach from the centre, in radii, for a core of {@code core}. */
    static float extent(float core) {
        return core + WOBBLE + WIDTH + STRAY_REACH + CELL;
    }

    /**
     * Is cell {@code (i, j)} (its lower-left corner at {@code (i, j) * CELL}) night? Solid inside the
     * core; past it, kept by chance as the edge thins; a kept cell with no kept neighbour beside it
     * is dropped, so the edge breaks into clumps rather than lone dots.
     */
    static boolean on(int i, int j, float core) {
        float t = raw(i, j, core);
        if (t >= 1f) return true;
        if (t <= 0f) return false;
        return raw(i + 1, j, core) > 0f || raw(i - 1, j, core) > 0f
            || raw(i, j + 1, core) > 0f || raw(i, j - 1, core) > 0f;
    }

    /** 1 for a solid cell, 0.5 for a cell kept by chance, 0 for sky. */
    static float raw(int i, int j, float core) {
        float x = (i + 0.5f) * CELL, y = (j + 0.5f) * CELL;
        float r = (float) Math.sqrt(x * x + y * y) - wobble((float) Math.atan2(y, x));
        if (r <= core) return 1f;
        float t = 1f - (r - core) / WIDTH;
        if (t > 0f && hash(i, j, 0) < t) return 0.5f;
        if (r <= core + WIDTH + STRAY_REACH && hash(i, j, 1) < STRAY) return 0.5f;
        return 0f;
    }

    /** The ragged outline: 0 to {@link #WOBBLE} outward, varying round the patch. */
    static float wobble(float angle) {
        float w = 0.5f + 0.3f * (float) Math.sin(angle * 5f) + 0.2f * (float) Math.sin(angle * 11f + 1f);
        return WOBBLE * Math.max(0f, Math.min(1f, w));
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
