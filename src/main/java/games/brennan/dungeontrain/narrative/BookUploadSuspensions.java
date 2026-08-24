package games.brennan.dungeontrain.narrative;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local mirror of the relay's book-upload suspension (see dp-relay {@code booksuspensions.js}).
 *
 * <p>The relay is the authority: it refuses a re-upload of a book the same player already uploaded
 * ({@code 409 duplicate_book}) and refuses every submit while the window is open ({@code 403
 * suspended}). This class caches the deadline it reports so the game can act on it locally —
 * without it, a suspended player keeps burning books & quills to uploads that are thrown away. With
 * it, {@code ServerGamePacketListenerImplSignBookMixin} lets vanilla sign the book instead, so the
 * player keeps what they wrote.</p>
 *
 * <p>Deliberately <b>ephemeral</b> (in-memory, cleared on restart): the relay re-rejects anything that
 * outlives this cache, so nothing is lost by forgetting — the cache only spares a burned book. Written
 * from the relay-response thread, read on the server thread; a {@link ConcurrentHashMap} of immutable
 * records is all the coordination that needs.</p>
 */
public final class BookUploadSuspensions {

    private BookUploadSuspensions() {}

    /** One player's open window: {@code untilMs} is a wall-clock deadline, {@code strikes} the relay's ladder position. */
    public record Suspension(long untilMs, int strikes) {}

    private static final Map<UUID, Suspension> ACTIVE = new ConcurrentHashMap<>();

    /** Record the relay's verdict for {@code player}. A deadline already in the past clears the entry. */
    public static void apply(UUID player, long untilMs, int strikes) {
        if (player == null) return;
        if (untilMs <= System.currentTimeMillis()) {
            ACTIVE.remove(player);
            return;
        }
        ACTIVE.put(player, new Suspension(untilMs, strikes));
    }

    /** True while {@code player} is known to be suspended from uploading. Expired entries are dropped. */
    public static boolean isSuspended(UUID player) {
        return remainingMs(player) > 0;
    }

    /** Milliseconds left on the window, or 0 when the player is free to upload. */
    public static long remainingMs(UUID player) {
        if (player == null) return 0L;
        Suspension s = ACTIVE.get(player);
        if (s == null) return 0L;
        long left = s.untilMs() - System.currentTimeMillis();
        if (left <= 0) {
            ACTIVE.remove(player);
            return 0L;
        }
        return left;
    }

    /** Whole seconds left, rounded UP so a sub-second remainder never reads as "0 seconds". */
    public static long remainingSec(UUID player) {
        long ms = remainingMs(player);
        return ms <= 0 ? 0L : (ms + 999L) / 1000L;
    }

    /** Forget {@code player}'s window (they served it, or the relay says they are clear). */
    public static void clear(UUID player) {
        if (player != null) ACTIVE.remove(player);
    }

    /** Test seam — forget every cached window. */
    public static void clearAll() {
        ACTIVE.clear();
    }
}
