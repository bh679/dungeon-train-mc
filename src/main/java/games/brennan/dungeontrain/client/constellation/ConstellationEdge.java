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

    /**
     * Opacity at {@code r}, the distance from the patch's centre as a fraction of its radius.
     * Soft: full to {@link #FEATHER_START}, then a smoothstep down to nothing at the rim. Hard: full
     * to the rim. Nothing beyond it either way.
     */
    public static float alphaAt(float r, boolean soft) {
        if (r >= 1f) return 0f;
        if (!soft || r <= FEATHER_START) return 1f;
        float t = (1f - r) / (1f - FEATHER_START);
        return t * t * (3f - 2f * t);
    }
}
