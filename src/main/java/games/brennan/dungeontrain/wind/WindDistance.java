package games.brennan.dungeontrain.wind;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;

/**
 * The one place that turns world X into wind distance. Forward of spawn it is {@code worldX - startX}; in the
 * reversed cycle behind spawn a column reads its forward twin's distance, so the reversed run blows like the
 * run it copies. Negative means calm.
 */
public final class WindDistance {

    private WindDistance() {}

    /** Wind distance at {@code worldX} using the configured cycle (the client keeps it in sync). */
    public static double at(double worldX, long startX) {
        if (startX == Long.MIN_VALUE) return -1.0;
        return at(WorldGenCycle.fromConfig(), worldX, startX);
    }

    public static double at(WorldGenCycle cycle, double worldX, long startX) {
        if (startX == Long.MIN_VALUE) return -1.0;
        long column = (long) Math.floor(worldX);
        if (!cycle.isMirroredAt(column)) return worldX - startX;
        return (double) (cycle.forwardTwinX(column) - startX) + (worldX - column);
    }
}
