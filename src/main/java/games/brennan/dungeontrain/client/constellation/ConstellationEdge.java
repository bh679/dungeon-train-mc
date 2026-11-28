package games.brennan.dungeontrain.client.constellation;

import java.util.Locale;

/**
 * How the constellation patch's rim meets what is behind it, chosen with the dev command
 * {@code /dungeontrain debug constellation-edge}. The patch is drawn in two passes, one over the
 * real sky and one over blocks, and each setting picks which rim each pass gets.
 */
public enum ConstellationEdge {
    /** Feathered against the real sky, crisp against blocks. The default. */
    MIXED(true, false),
    /** Feathered everywhere. */
    SOFT(true, true),
    /** Crisp everywhere. */
    HARD(false, false);

    /** Where the feather starts, as a fraction of the radius: the rim's outer {@code 1 - this} fades. */
    public static final float FEATHER_START = 0.72f;

    private static volatile ConstellationEdge current = MIXED;

    private final boolean softOverSky;
    private final boolean softOverBlocks;

    ConstellationEdge(boolean softOverSky, boolean softOverBlocks) {
        this.softOverSky = softOverSky;
        this.softOverBlocks = softOverBlocks;
    }

    public boolean softOverSky() {
        return softOverSky;
    }

    public boolean softOverBlocks() {
        return softOverBlocks;
    }

    public static ConstellationEdge current() {
        return current;
    }

    public static void set(ConstellationEdge edge) {
        current = edge == null ? MIXED : edge;
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
