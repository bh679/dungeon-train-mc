package games.brennan.dungeontrain.client.constellation;

import java.util.Locale;

/**
 * How the constellation patch's rim meets the real sky (and clouds, which count as sky), chosen with
 * the dev command {@code /dungeontrain-debug-constellation-edge}.
 *
 * <p>Blocks are never cut by the rim, whichever setting: a block in front of a star is hidden whole and
 * looks like the sky round it, every other block draws as normal ({@link ConstellationOcclusion}).</p>
 */
public enum ConstellationEdge {
    /** Feathered against the sky. The default. */
    SOFT(true),
    /** Crisp against the sky. */
    HARD(false);

    /** Where the feather starts, as a fraction of the radius: the rim's outer {@code 1 - this} fades. */
    public static final float FEATHER_START = 0.72f;

    private static volatile ConstellationEdge current = SOFT;

    private final boolean soft;

    ConstellationEdge(boolean soft) {
        this.soft = soft;
    }

    /** Does the rim feather against the sky? */
    public boolean soft() {
        return soft;
    }

    public static ConstellationEdge current() {
        return current;
    }

    public static void set(ConstellationEdge edge) {
        current = edge == null ? SOFT : edge;
    }

    /** By name, any case; null when there is no such setting. */
    public static ConstellationEdge parse(String name) {
        if (name == null) return null;
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** How wide the soft rim is, in radii, outside the solid core. */
    public static final float FEATHER_WIDTH = 1f - FEATHER_START;

    /** {@link #alphaAt(float, boolean, float)} with the core at its smallest, {@link #FEATHER_START}. */
    public static float alphaAt(float r, boolean soft) {
        return alphaAt(r, soft, FEATHER_START);
    }

    /**
     * Opacity at {@code r}, the distance from the patch's centre in radii, with the solid core reaching
     * out to {@code core}. Soft: full to the core, then a smoothstep down to nothing
     * {@link #FEATHER_WIDTH} further out. Hard: full to {@link #rim}, nothing past it.
     */
    public static float alphaAt(float r, boolean soft, float core) {
        if (r >= rim(soft, core)) return 0f;
        if (!soft || r <= core) return 1f;
        float t = (core + FEATHER_WIDTH - r) / FEATHER_WIDTH;
        return t * t * (3f - 2f * t);
    }

    /** Where the patch ends: the feather's outer edge (soft), or the core or the unit disc, whichever is wider (hard). */
    public static float rim(boolean soft, float core) {
        return soft ? core + FEATHER_WIDTH : Math.max(1f, core);
    }
}
