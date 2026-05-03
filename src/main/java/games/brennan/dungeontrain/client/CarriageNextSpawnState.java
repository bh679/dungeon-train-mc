package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.net.CarriageNextSpawnPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side cache of the most recent
 * {@link CarriageNextSpawnPacket} snapshot — the planned-next-spawn for
 * every train that has a queued anchor. Read by
 * {@link CarriageGroupGapDebugRenderer} to draw a wireframe preview at
 * each planned position.
 *
 * <p>Replacement semantics mirror {@link CarriageGroupGapState}: every
 * packet replaces the cache wholesale, an empty packet clears it, and
 * reads return a defensive copy so the render thread doesn't hold the
 * monitor for the duration of a frame.</p>
 */
public final class CarriageNextSpawnState {

    private static final List<CarriageNextSpawnPacket.Entry> CACHE = new ArrayList<>();

    private CarriageNextSpawnState() {}

    public static synchronized void applySnapshot(CarriageNextSpawnPacket packet) {
        CACHE.clear();
        if (!packet.isEmpty()) {
            CACHE.addAll(packet.entries());
        }
    }

    public static synchronized List<CarriageNextSpawnPacket.Entry> snapshot() {
        return new ArrayList<>(CACHE);
    }
}
