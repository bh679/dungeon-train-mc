package games.brennan.dungeontrain.wind;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;

/**
 * The one place that turns world X into wind distance. Forward of spawn it is {@code worldX - startX}. Behind
 * spawn, in the reversed cycle, the wind builds band by band as the train travels -X: each reversed band ramps
 * from its target to the next one's ({@link WindProfile#reverseDistance}). Negative means calm.
 */
public final class WindDistance {

    private WindDistance() {}

    /** Wind distance at {@code worldX} using the configured cycle (the client keeps it in sync). */
    public static double at(WindProfile profile, double worldX, long startX) {
        if (startX == Long.MIN_VALUE) return -1.0;
        return at(profile, WorldGenCycle.fromConfig(), worldX, startX);
    }

    public static double at(WindProfile profile, WorldGenCycle cycle, double worldX, long startX) {
        if (startX == Long.MIN_VALUE) return -1.0;
        long column = (long) Math.floor(worldX);
        if (!cycle.isMirroredAt(column)) return worldX - startX;
        long band = cycle.reverseBandIndex(column);
        if (band < 0L) return -1.0;
        return profile.reverseDistance(band, cycle.reverseBandProgress(worldX));
    }
}
