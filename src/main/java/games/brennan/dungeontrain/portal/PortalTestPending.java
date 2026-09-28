package games.brennan.dungeontrain.portal;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test the Carriage presses still waiting on their room's chunk sample.
 *
 * <p>A chunk dimension stands its doorways on the ground its sample landed, so there is nothing to
 * stamp until that sample is in hand — and it lands on a worker a moment after the press asked for
 * it. The press used to answer "run this again in a second" and leave the author to guess when; it
 * is filed here instead, and {@code PortalTestTicker} finishes it the tick the sample lands.</p>
 *
 * <p>Session-only, like {@link PortalTestSession}: nothing is persisted, and a server stopping drops
 * every entry.</p>
 */
public final class PortalTestPending {

    /**
     * How long a press waits for its sample before giving up, in server ticks.
     *
     * <p>A sample is normally in hand in well under a second. Thirty seconds covers a sampler thread
     * that is still working through a run of the train's own chunk dimensions ahead of this one.</p>
     */
    public static final long TIMEOUT_TICKS = 20L * 30;

    /**
     * One press waiting on its sample.
     *
     * @param roomName     the room to stamp once the sample lands
     * @param freshRoll    what the press asked of the contents roll, passed through unchanged
     * @param deadlineTick the overworld game time after which the press is abandoned
     */
    public record Pending(String roomName, boolean freshRoll, long deadlineTick) {

        public boolean expired(long nowTick) {
            return nowTick > deadlineTick;
        }
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private PortalTestPending() {}

    /** File a press. A second press while one is waiting replaces it — one room, one stamp. */
    public static void put(UUID player, String roomName, boolean freshRoll, long nowTick) {
        PENDING.put(player, new Pending(roomName, freshRoll, nowTick + TIMEOUT_TICKS));
    }

    /** Every waiting press, for the ticker. */
    public static java.util.Set<Map.Entry<UUID, Pending>> entries() {
        return PENDING.entrySet();
    }

    /** Drop a press — only if it is still {@code expected}, so a newer press is never lost. */
    public static void remove(UUID player, Pending expected) {
        PENDING.remove(player, expected);
    }

    /**
     * Drop whatever this player is waiting on — Back, or a newer press.
     *
     * @return whether there was a press to drop
     */
    public static boolean cancel(UUID player) {
        return PENDING.remove(player) != null;
    }

    public static boolean isEmpty() {
        return PENDING.isEmpty();
    }

    public static void clear() {
        PENDING.clear();
    }
}
