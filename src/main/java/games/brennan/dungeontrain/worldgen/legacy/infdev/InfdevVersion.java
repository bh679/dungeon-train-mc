package games.brennan.dungeontrain.worldgen.legacy.infdev;

/**
 * The Infdev snapshots, oldest first. The band plays them <em>newest first</em> — the legacy run heads
 * back in time from Alpha — so 611 (near-Alpha) takes the first 20%, then 420 and 415 get 20% each and
 * 227 (it carries the landmarks) the last 40%, running on into Classic. Each version seam is a hard chunk
 * wall, the way a real Infdev save looked across an update.
 */
public enum InfdevVersion {
    /** Infdev 20100227 — 2-D heightmap terrain, brick pyramids and obsidian walls, no caves. */
    V227,
    /** Infdev 20100415 — the first 3-D density terrain: tall, jagged, no caves. */
    V415,
    /** Infdev 20100420 — Alpha-style density blending, caves. */
    V420,
    /** Infdev 20100611 — near-Alpha terrain from scale/depth noise, caves. */
    V611;

    /** Share of the band (entry fade → exit fade) where each older version takes over. */
    private static final double V420_FROM = 0.2D;
    private static final double V415_FROM = 0.4D;
    private static final double V227_FROM = 0.6D;

    /**
     * The version at {@code progress} ({@code 0..1}) through the band. Out-of-range values clamp, so the
     * entry fade reads as 611 and the exit fade as 227.
     */
    public static InfdevVersion at(double progress) {
        if (progress < V420_FROM) return V611;
        if (progress < V415_FROM) return V420;
        if (progress < V227_FROM) return V415;
        return V227;
    }

    /** True for the three 3-D density generators (everything after 227). */
    public boolean isDensity() {
        return this != V227;
    }
}
