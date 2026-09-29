package games.brennan.dungeontrain.ship.sable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which players Sable just full-synced a sub-level to, so their next few movement
 * snapshots can take the ordered connection instead of Sable's UDP-style pipeline.
 *
 * <p><b>Why.</b> Sable's {@code SubLevelTrackingSystem} starts tracking a sub-level with a
 * {@code sendFullSync} bundle (StartTracking + plot chunks + Finalize) over the normal, ordered
 * connection, then — in the <em>same</em> tracking tick — sends a MOVE snapshot for it. For a
 * UDP-connected player (the default in singleplayer, where Sable's memory "UDP" pipeline hands the
 * packet straight to the client's {@code SableClient.NETWORK_EVENT_LOOP}) that snapshot skips the
 * connection's ordering and can be handled before the full sync is, so the client logs
 * "Received a sub-level movement packet for a non-existent sub-level". A new sub-level always
 * sends that MOVE: {@code ServerSubLevel.lastNetworkedPose} starts at the origin. Dungeon Train
 * hits this on every appended carriage group (~1 error per 2 spawns in a 2026-09-29 ride) and on
 * any group that enters tracking range, reloads from holding, or is full-synced after a dimension
 * change. See {@code mixin.SubLevelTrackingOrderedSnapshotMixin}.</p>
 *
 * <p>Keyed by player UUID with the server tick of the latest full sync. Expired entries are pruned
 * on every record, so the map never holds more than the players synced in the last window.</p>
 */
public final class RecentFullSyncTracker {

    /**
     * How long, in server ticks, a player's snapshots stay on the ordered connection after a full
     * sync. One tick closes the same-tick race; the rest covers the plot-chunk bundle taking longer
     * than a tick to decode and the back-to-back catch-up ticks a lagging server runs.
     */
    public static final long ORDERED_SNAPSHOT_WINDOW_TICKS = 10L;

    private static final Map<UUID, Long> LAST_FULL_SYNC_TICK = new ConcurrentHashMap<>();

    private RecentFullSyncTracker() {}

    /** Record that {@code player} was just full-synced a sub-level at {@code serverTick}. */
    public static void recordFullSync(UUID player, long serverTick) {
        LAST_FULL_SYNC_TICK.entrySet().removeIf(e -> !isWithinWindow(serverTick, e.getValue(), ORDERED_SNAPSHOT_WINDOW_TICKS));
        LAST_FULL_SYNC_TICK.put(player, serverTick);
    }

    /** True while {@code player}'s movement snapshots should use the ordered connection. */
    public static boolean needsOrderedSnapshots(UUID player, long serverTick) {
        Long syncTick = LAST_FULL_SYNC_TICK.get(player);
        return syncTick != null && isWithinWindow(serverTick, syncTick, ORDERED_SNAPSHOT_WINDOW_TICKS);
    }

    /**
     * Whether {@code nowTick} falls in {@code [syncTick, syncTick + window]}. A {@code syncTick}
     * in the future — left over from a previous server in the same JVM, whose tick count started
     * higher — is outside the window, so it can't pin a player to the ordered path.
     */
    static boolean isWithinWindow(long nowTick, long syncTick, long window) {
        long elapsed = nowTick - syncTick;
        return elapsed >= 0 && elapsed <= window;
    }

    /** Test hook. */
    static void clear() {
        LAST_FULL_SYNC_TICK.clear();
    }
}
