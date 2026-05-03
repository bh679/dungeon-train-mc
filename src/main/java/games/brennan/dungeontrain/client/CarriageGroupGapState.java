package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.net.CarriageGroupGapPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side cache of the most recent
 * {@link CarriageGroupGapPacket} snapshot. Read by
 * {@link VersionHudOverlay} to render the "next-group distance" line under
 * the carriage index whenever the player is standing in a carriage that
 * isn't in the leading group.
 *
 * <p>Cache discipline: the snapshot is replaced wholesale on every packet —
 * no per-entry merge — to avoid stale entries lingering after a group is
 * unloaded. An empty packet clears the cache.</p>
 *
 * <p>Concurrency: writes happen on the client main thread (via
 * {@code IPayloadContext.enqueueWork}); reads happen on the render thread
 * (HUD draw). The static methods are {@code synchronized} on the class
 * monitor so the render thread sees a consistent snapshot.</p>
 */
public final class CarriageGroupGapState {

    private static final List<CarriageGroupGapPacket.Entry> CACHE = new ArrayList<>();

    private CarriageGroupGapState() {}

    public static synchronized void applySnapshot(CarriageGroupGapPacket packet) {
        CACHE.clear();
        if (!packet.isEmpty()) {
            CACHE.addAll(packet.entries());
        }
    }

    /**
     * Find the gap entry whose source group covers {@code carriagePIdx}, or
     * {@code null} if the player's carriage is in the leading group of its
     * train (no "next group") or not in any tracked train.
     */
    public static synchronized CarriageGroupGapPacket.Entry findByCarriage(int carriagePIdx) {
        for (CarriageGroupGapPacket.Entry e : CACHE) {
            if (carriagePIdx >= e.anchorPIdx() && carriagePIdx <= e.highestPIdx()) {
                return e;
            }
        }
        return null;
    }

    /**
     * Defensive copy of the current cache, for the world-space debug
     * renderer to iterate without holding the State's monitor for the
     * duration of the render pass.
     */
    public static synchronized List<CarriageGroupGapPacket.Entry> snapshot() {
        return new ArrayList<>(CACHE);
    }
}
