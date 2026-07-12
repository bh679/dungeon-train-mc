package games.brennan.dungeontrain.event;

import java.util.List;

/**
 * Pure, registry-free nearest-candidate selector. Decoupled from Minecraft
 * registry types so it can be unit-tested without a NeoForge bootstrap —
 * mirroring the pure-logic testing approach used for
 * {@link VillagerTrainSpawnEvents#pickLevel}.
 *
 * <p>Used by {@link VillagerJobSiteAssigner} to pick the closest job-site block
 * to a villager from the candidates gathered by scanning the carriage plot.</p>
 */
public final class NearestPicker {

    private NearestPicker() {}

    /**
     * A candidate point in space carrying an arbitrary payload.
     *
     * @param x       candidate X (typically a block centre, in shipyard coords)
     * @param y       candidate Y
     * @param z       candidate Z
     * @param payload value returned when this candidate is the nearest
     * @param <T>     payload type
     */
    public record Located<T>(double x, double y, double z, T payload) {}

    /**
     * Returns the payload of the candidate whose Euclidean distance to
     * {@code (ox, oy, oz)} is smallest and {@code <= maxDist}, or {@code null}
     * when the list is empty or every candidate lies beyond {@code maxDist}.
     *
     * <p>Deterministic: on an exact distance tie the <em>first</em> candidate in
     * iteration order wins (strict {@code <} comparison). Callers pass candidates
     * in {@code BlockPos.betweenClosed} scan order, which is fixed, so the result
     * is stable for a given world state.</p>
     *
     * @param ox         origin X
     * @param oy         origin Y
     * @param oz         origin Z
     * @param maxDist    inclusive maximum distance; candidates farther than this
     *                   are ignored
     * @param candidates candidates to choose from (never {@code null})
     * @param <T>        payload type
     */
    public static <T> T nearestWithin(double ox, double oy, double oz,
                                      double maxDist, List<Located<T>> candidates) {
        double maxSq = maxDist * maxDist;
        double bestSq = Double.POSITIVE_INFINITY;
        T best = null;
        for (Located<T> c : candidates) {
            double dx = c.x() - ox;
            double dy = c.y() - oy;
            double dz = c.z() - oz;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > maxSq) continue;
            if (distSq < bestSq) {
                bestSq = distSq;
                best = c.payload();
            }
        }
        return best;
    }
}
