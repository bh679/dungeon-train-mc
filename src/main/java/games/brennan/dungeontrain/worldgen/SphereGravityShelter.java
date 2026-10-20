package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.wind.TrainExposure;
import net.minecraft.world.entity.Entity;

/**
 * How much of a sphere's pull reaches an entity on the train. Shared by the server (mobs, items,
 * projectiles) and the client (its own player) so both make the same call from the same carriage blocks.
 *
 * <p>The carriage scans are the train wind's ({@link TrainExposure#classifyCover}: deck under the feet, side
 * walls, roof overhead, all read in Sable's plot frame), minus the wind's tunnel and lee tests. Three
 * outcomes:</p>
 * <ul>
 *   <li>{@link Exposure#FREE} — not over a carriage: the full pull.</li>
 *   <li>{@link Exposure#RIDER} — out on the train (a roof, a flatbed, a coupling pad): the rider factor.</li>
 *   <li>{@link Exposure#SHELTERED} — inside a carriage, under its roof or between its walls: no pull.</li>
 * </ul>
 */
public final class SphereGravityShelter {

    private SphereGravityShelter() {}

    public enum Exposure {
        FREE, RIDER, SHELTERED
    }

    /** {@link #classify} as a pull multiplier. */
    public static double factor(Entity e, double riderFactor) {
        return switch (classify(e)) {
            case FREE -> 1.0;
            case RIDER -> riderFactor;
            case SHELTERED -> 0.0;
        };
    }

    public static Exposure classify(Entity e) {
        TrainExposure.Result r = TrainExposure.classifyCover(TrainExposure.subLevelUnder(e.level(), e), e);
        return switch (r) {
            case NOT_ON_TRAIN -> Exposure.FREE;
            case UNDER_COVER, IN_TUNNEL, IN_LEE -> Exposure.SHELTERED;
            case EXPOSED -> Exposure.RIDER;
        };
    }
}
