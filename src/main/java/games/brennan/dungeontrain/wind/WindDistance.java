package games.brennan.dungeontrain.wind;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;

/**
 * The one place that turns world X into wind distance. Forward of spawn it is {@code worldX - startX}. Behind
 * spawn, in the reversed cycle, counting bands from spawn both ways, the Nth band met travelling -X plays the
 * wind of the (N + {@link WindProfile#reverseBandShift})th band reached travelling +X
 * ({@link WorldGenCycle#reverseWindTwinX}), so the wind builds band by band. Negative means calm.
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
        double twin = cycle.reverseWindTwinX(worldX, profile.reverseBandShift());
        return Double.isNaN(twin) ? -1.0 : twin - startX;
    }
}
