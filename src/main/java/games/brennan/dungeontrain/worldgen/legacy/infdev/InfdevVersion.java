package games.brennan.dungeontrain.worldgen.legacy.infdev;

/**
 * The Infdev snapshots the Infdev band steps through, oldest first. The band's length is split between
 * them — 227 gets the first 40% (it carries the landmarks), then 415, 420 and 611 get 20% each — so the
 * terrain "updates" as the train rides on, with a hard chunk wall at every version seam, the way a real
 * Infdev save looked after an update.
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

    /** Share of the band (entry fade → exit fade) where each later version takes over. */
    private static final double V415_FROM = 0.4D;
    private static final double V420_FROM = 0.6D;
    private static final double V611_FROM = 0.8D;

    /**
     * The version at {@code progress} ({@code 0..1}) through the band. Out-of-range values clamp, so the
     * entry fade reads as 227 and the exit fade as 611.
     */
    public static InfdevVersion at(double progress) {
        if (progress < V415_FROM) return V227;
        if (progress < V420_FROM) return V415;
        if (progress < V611_FROM) return V420;
        return V611;
    }

    /** True for the three 3-D density generators (everything after 227). */
    public boolean isDensity() {
        return this != V227;
    }
}
