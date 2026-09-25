package games.brennan.dungeontrain.client;

/**
 * Where to put Distant Horizons' far fog so that it turns fully opaque at the render cap — a fog wall
 * that hides everything past a void, or past the next legacy era, without touching DH's render
 * distance. DH positions its far fog as a percent of its render radius and re-reads it every frame, so
 * moving the wall costs nothing and the boundary slides smoothly; a render-distance change would rebuild
 * DH's LOD tree instead. Pure (no DH types), so it is unit-tested.
 */
public final class DhFogWall {

    /** Longest stretch, in blocks, over which the wall thickens from the player's fog to opaque. */
    static final double FADE_MAX_BLOCKS = 192.0;
    /** Share of the cap distance the fade may take when the cap itself is short. */
    static final double FADE_SHARE = 0.5;
    /** Smallest end percent handed to DH, so start and end never coincide. */
    static final float MIN_END_PERCENT = 1.0e-4f;

    private DhFogWall() {}

    /** Far-fog start and end, as percents of DH's render radius, both {@code 0..1}. */
    public record Fog(float startPercent, float endPercent) {}

    /**
     * The fog to draw for an allowed distance, or {@code null} when the player's own fog already hides
     * everything the cap would (or the cap reaches past DH's render radius, so there is nothing to hide).
     *
     * @param capBlocks     how far DH may show, in blocks
     * @param radiusBlocks  DH's render radius in blocks
     * @param userStart     the player's far-fog start percent
     * @param userEnd       the player's far-fog end percent
     * @param userMaxOpaque the player's far-fog thickness at its thickest ({@code 1} = opaque)
     */
    public static Fog wall(double capBlocks, double radiusBlocks, float userStart, float userEnd, float userMaxOpaque) {
        if (radiusBlocks <= 0.0 || capBlocks >= radiusBlocks) return null;
        float end = (float) Math.max(MIN_END_PERCENT, Math.max(0.0, capBlocks) / radiusBlocks);
        if (userMaxOpaque >= 1.0f && userEnd <= end && userStart <= userEnd) return null;
        double fade = Math.min(FADE_MAX_BLOCKS, Math.max(0.0, capBlocks) * FADE_SHARE);
        float start = (float) Math.max(0.0, (capBlocks - fade) / radiusBlocks);
        if (userStart < start && userStart <= userEnd) start = userStart;
        return new Fog(Math.min(start, end * 0.999f), end);
    }
}
