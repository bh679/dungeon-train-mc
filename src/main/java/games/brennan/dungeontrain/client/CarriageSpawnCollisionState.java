package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.net.CarriageSpawnCollisionPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side cache of the most recent
 * {@link CarriageSpawnCollisionPacket} snapshot — the post-spawn 1×3×5
 * collision-check box per train, with green/red flag. Read by
 * {@link CarriageGroupGapDebugRenderer} to draw the diagnostic wireframe
 * at the back of each freshly-spawned carriage.
 *
 * <p>Replacement semantics mirror {@link CarriageNextSpawnState}: every
 * packet replaces the cache wholesale, an empty packet clears it, and
 * reads return a defensive copy.</p>
 */
public final class CarriageSpawnCollisionState {

    private static final List<CarriageSpawnCollisionPacket.Entry> CACHE = new ArrayList<>();

    private CarriageSpawnCollisionState() {}

    public static synchronized void applySnapshot(CarriageSpawnCollisionPacket packet) {
        CACHE.clear();
        if (!packet.isEmpty()) {
            CACHE.addAll(packet.entries());
        }
    }

    public static synchronized List<CarriageSpawnCollisionPacket.Entry> snapshot() {
        return new ArrayList<>(CACHE);
    }
}
